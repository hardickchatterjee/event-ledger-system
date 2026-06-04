package com.example.accountService.service;

import com.example.accountService.model.*;
import com.example.accountService.repository.AccountRepository;
import com.example.accountService.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService Unit Tests")
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private AccountService accountService;

    private TransactionRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new TransactionRequest();
        validRequest.setEventId("evt-001");
        validRequest.setType(TransactionType.CREDIT);
        validRequest.setAmount(new BigDecimal("100.00"));
        validRequest.setCurrency("USD");
    }

    @Test
    @DisplayName("Should apply transaction successfully and create account if not exists")
    void testApplyTransactionNewAccount() {
        String accountId = "new-acct-001";

        when(transactionRepository.existsByEventId("evt-001")).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        Account newAccount = Account.builder().accountId(accountId).build();
        when(accountRepository.save(any(Account.class))).thenReturn(newAccount);

        Transaction savedTx = Transaction.builder()
            .transactionId(UUID.randomUUID())
            .eventId("evt-001")
            .accountId(accountId)
            .type(TransactionType.CREDIT)
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .build();

        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTx);

        TransactionResponse response = accountService.applyTransaction(accountId, validRequest);

        assertNotNull(response);
        assertEquals("evt-001", response.getEventId());
        assertEquals(accountId, response.getAccountId());
        assertEquals(TransactionType.CREDIT, response.getType());
        verify(accountRepository, times(1)).save(any(Account.class));
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Should apply transaction to existing account")
    void testApplyTransactionExistingAccount() {
        String accountId = "existing-acct-001";
        Account existingAccount = Account.builder().accountId(accountId).build();

        when(transactionRepository.existsByEventId("evt-001")).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(existingAccount));

        Transaction savedTx = Transaction.builder()
            .transactionId(UUID.randomUUID())
            .eventId("evt-001")
            .accountId(accountId)
            .type(TransactionType.CREDIT)
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .build();

        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTx);

        TransactionResponse response = accountService.applyTransaction(accountId, validRequest);

        assertNotNull(response);
        assertEquals("evt-001", response.getEventId());
        // Should NOT create new account
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Should return existing transaction for duplicate eventId (idempotency)")
    void testApplyTransactionIdempotency() {
        String accountId = "acct-dup-001";
        Transaction existingTx = Transaction.builder()
            .transactionId(UUID.randomUUID())
            .eventId("evt-001")
            .accountId(accountId)
            .type(TransactionType.CREDIT)
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .build();

        when(transactionRepository.existsByEventId("evt-001")).thenReturn(true);
        when(transactionRepository.findByEventId("evt-001")).thenReturn(Optional.of(existingTx));

        TransactionResponse response = accountService.applyTransaction(accountId, validRequest);

        assertNotNull(response);
        assertEquals("evt-001", response.getEventId());
        assertEquals(existingTx.getTransactionId(), response.getTransactionId());
        // Should NOT save new transaction
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Should handle DB-level duplicate constraint violation gracefully")
    void testApplyTransactionDatabaseLevelDuplicate() {
        String accountId = "acct-dbdup-001";
        Account account = Account.builder().accountId(accountId).build();

        when(transactionRepository.existsByEventId("evt-001")).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        // Simulate database constraint violation on save
        when(transactionRepository.save(any(Transaction.class)))
            .thenThrow(new DataIntegrityViolationException("Duplicate key violation"));

        Transaction existingTx = Transaction.builder()
            .transactionId(UUID.randomUUID())
            .eventId("evt-001")
            .accountId(accountId)
            .type(TransactionType.CREDIT)
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .build();

        when(transactionRepository.findByEventId("evt-001")).thenReturn(Optional.of(existingTx));

        TransactionResponse response = accountService.applyTransaction(accountId, validRequest);

        assertNotNull(response);
        assertEquals("evt-001", response.getEventId());
    }

    @Test
    @DisplayName("Should compute balance for account")
    void testGetBalance() {
        String accountId = "acct-balance-001";
        Account account = Account.builder().accountId(accountId).build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(transactionRepository.computeBalance(accountId)).thenReturn(new BigDecimal("150.00"));
        when(transactionRepository.countByAccountId(accountId)).thenReturn(2L);

        BalanceResponse response = accountService.getBalance(accountId);

        assertNotNull(response);
        assertEquals(accountId, response.getAccountId());
        assertEquals(new BigDecimal("150.00"), response.getBalance());
        assertEquals(2L, response.getTransactionCount());
    }

    @Test
    @DisplayName("Should throw 404 when account not found for getBalance")
    void testGetBalanceAccountNotFound() {
        when(accountRepository.findById("nonexistent")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> accountService.getBalance("nonexistent"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("Should retrieve account details with balance and recent transactions")
    void testGetAccountDetails() {
        String accountId = "acct-details-001";
        Account account = Account.builder()
            .accountId(accountId)
            .createdAt(Instant.now())
            .build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(transactionRepository.computeBalance(accountId)).thenReturn(new BigDecimal("250.00"));

        Transaction tx1 = Transaction.builder()
            .transactionId(UUID.randomUUID())
            .eventId("evt-001")
            .accountId(accountId)
            .type(TransactionType.CREDIT)
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .build();

        Transaction tx2 = Transaction.builder()
            .transactionId(UUID.randomUUID())
            .eventId("evt-002")
            .accountId(accountId)
            .type(TransactionType.DEBIT)
            .amount(new BigDecimal("50.00"))
            .currency("USD")
            .build();

        when(transactionRepository.findByAccountIdOrderByAppliedAtDesc(accountId))
            .thenReturn(List.of(tx2, tx1));

        AccountDetailsResponse response = accountService.getAccountDetails(accountId);

        assertNotNull(response);
        assertEquals(accountId, response.getAccountId());
        assertEquals(new BigDecimal("250.00"), response.getBalance());
        assertEquals(2, response.getRecentTransactions().size());
    }

    @Test
    @DisplayName("Should throw 404 when account not found for getAccountDetails")
    void testGetAccountDetailsAccountNotFound() {
        when(accountRepository.findById("nonexistent")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> accountService.getAccountDetails("nonexistent"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("Should handle DEBIT transaction correctly")
    void testApplyDebitTransaction() {
        String accountId = "acct-debit-001";
        Account account = Account.builder().accountId(accountId).build();

        TransactionRequest debitRequest = new TransactionRequest();
        debitRequest.setEventId("evt-debit-001");
        debitRequest.setType(TransactionType.DEBIT);
        debitRequest.setAmount(new BigDecimal("50.00"));
        debitRequest.setCurrency("USD");

        when(transactionRepository.existsByEventId("evt-debit-001")).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        Transaction savedTx = Transaction.builder()
            .transactionId(UUID.randomUUID())
            .eventId("evt-debit-001")
            .accountId(accountId)
            .type(TransactionType.DEBIT)
            .amount(new BigDecimal("50.00"))
            .currency("USD")
            .build();

        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTx);

        TransactionResponse response = accountService.applyTransaction(accountId, debitRequest);

        assertNotNull(response);
        assertEquals(TransactionType.DEBIT, response.getType());
        assertEquals(new BigDecimal("50.00"), response.getAmount());
    }
}
