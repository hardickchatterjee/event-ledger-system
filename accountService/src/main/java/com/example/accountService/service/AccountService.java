package com.example.accountService.service;

import com.example.accountService.model.Account;
import com.example.accountService.model.AccountDetailsResponse;
import com.example.accountService.model.BalanceResponse;
import com.example.accountService.model.Transaction;
import com.example.accountService.model.TransactionRequest;
import com.example.accountService.model.TransactionResponse;
import com.example.accountService.repository.AccountRepository;
import com.example.accountService.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public TransactionResponse applyTransaction(String accountId, TransactionRequest request) {

        if (transactionRepository.existsByEventId(request.getEventId())) {
            log.info("Duplicate eventId={} for accountId={}, returning idempotent response",
                request.getEventId(), accountId);
            Transaction existing = transactionRepository.findByEventId(request.getEventId()).get();
            return toResponse(existing);
        }

        accountRepository.findById(accountId).orElseGet(() -> {
            Account newAccount = Account.builder().accountId(accountId).build();
            return accountRepository.save(newAccount);
        });

        Transaction tx = Transaction.builder()
            .eventId(request.getEventId())
            .accountId(accountId)
            .type(request.getType())
            .amount(request.getAmount())
            .currency(request.getCurrency())
            .build();

        try {
            tx = transactionRepository.save(tx);
        } catch (DataIntegrityViolationException ex) {
            log.warn("DB-level duplicate detected for eventId={}", request.getEventId());
            Transaction existing = transactionRepository.findByEventId(request.getEventId()).get();
            return toResponse(existing);
        }

        return toResponse(tx);
    }

    public BalanceResponse getBalance(String accountId) {
        requireAccountExists(accountId);
        BigDecimal balance = transactionRepository.computeBalance(accountId);
        long count = transactionRepository.countByAccountId(accountId);
        return BalanceResponse.builder()
            .accountId(accountId)
            .balance(balance)
            .currency("USD")
            .transactionCount(count)
            .build();
    }

    public AccountDetailsResponse getAccountDetails(String accountId) {
        Account account = requireAccountExists(accountId);
        BigDecimal balance = transactionRepository.computeBalance(accountId);
        List<TransactionResponse> recent = transactionRepository
            .findByAccountIdOrderByAppliedAtDesc(accountId)
            .stream()
            .limit(20)
            .map(this::toResponse)
            .collect(Collectors.toList());

        return AccountDetailsResponse.builder()
            .accountId(account.getAccountId())
            .createdAt(account.getCreatedAt())
            .balance(balance)
            .recentTransactions(recent)
            .build();
    }

    private Account requireAccountExists(String accountId) {
        return accountRepository.findById(accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Account not found: " + accountId));
    }

    private TransactionResponse toResponse(Transaction tx) {
        return TransactionResponse.builder()
            .transactionId(tx.getTransactionId())
            .eventId(tx.getEventId())
            .accountId(tx.getAccountId())
            .type(tx.getType())
            .amount(tx.getAmount())
            .currency(tx.getCurrency())
            .appliedAt(tx.getAppliedAt())
            .build();
    }
}
