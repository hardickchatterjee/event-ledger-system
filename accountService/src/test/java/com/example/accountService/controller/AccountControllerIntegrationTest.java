package com.example.accountService.controller;

import com.example.accountService.model.TransactionRequest;
import com.example.accountService.model.TransactionType;
import com.example.accountService.service.AccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("AccountController Integration Tests")
class AccountControllerIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Test
    @DisplayName("Should apply transaction successfully")
    void testApplyTransactionSuccess() {
        String accountId = "acct-integration-001";

        TransactionRequest request = new TransactionRequest();
        request.setEventId("evt-integration-001");
        request.setType(TransactionType.CREDIT);
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency("USD");

        var response = accountService.applyTransaction(accountId, request);

        assertNotNull(response);
        assertEquals("evt-integration-001", response.getEventId());
        assertEquals(accountId, response.getAccountId());
        assertEquals(TransactionType.CREDIT, response.getType());
    }

    @Test
    @DisplayName("Should auto-create account on first transaction")
    void testApplyTransactionAutoCreatesAccount() {
        String accountId = "acct-autocreate-001";

        TransactionRequest request = new TransactionRequest();
        request.setEventId("evt-autocreate-001");
        request.setType(TransactionType.CREDIT);
        request.setAmount(new BigDecimal("50.00"));
        request.setCurrency("USD");

        accountService.applyTransaction(accountId, request);

        var details = accountService.getAccountDetails(accountId);
        assertNotNull(details);
        assertEquals(accountId, details.getAccountId());
    }

    @Test
    @DisplayName("Should return same transaction response for duplicate eventId (idempotency)")
    void testApplyTransactionIdempotency() {
        String accountId = "acct-idempotent-001";

        TransactionRequest request = new TransactionRequest();
        request.setEventId("evt-idempotent-001");
        request.setType(TransactionType.CREDIT);
        request.setAmount(new BigDecimal("75.00"));
        request.setCurrency("USD");

        var response1 = accountService.applyTransaction(accountId, request);
        var response2 = accountService.applyTransaction(accountId, request);

        assertNotNull(response1);
        assertNotNull(response2);
        assertEquals(response1.getEventId(), response2.getEventId());
        assertEquals(response1.getTransactionId(), response2.getTransactionId());
    }

    @Test
    @DisplayName("Should compute balance correctly for account with multiple transactions")
    void testGetBalance() {
        String accountId = "acct-balance-001";

        // Submit CREDIT transaction
        TransactionRequest creditRequest = new TransactionRequest();
        creditRequest.setEventId("evt-balance-credit-001");
        creditRequest.setType(TransactionType.CREDIT);
        creditRequest.setAmount(new BigDecimal("100.00"));
        creditRequest.setCurrency("USD");

        accountService.applyTransaction(accountId, creditRequest);

        // Submit DEBIT transaction
        TransactionRequest debitRequest = new TransactionRequest();
        debitRequest.setEventId("evt-balance-debit-001");
        debitRequest.setType(TransactionType.DEBIT);
        debitRequest.setAmount(new BigDecimal("30.00"));
        debitRequest.setCurrency("USD");

        accountService.applyTransaction(accountId, debitRequest);

        // Get balance and verify computation
        var balance = accountService.getBalance(accountId);
        assertNotNull(balance);
        assertEquals(accountId, balance.getAccountId());
        assertEquals(0, balance.getBalance().compareTo(new BigDecimal("70.00")));
        assertEquals(2, balance.getTransactionCount());
    }

    @Test
    @DisplayName("Should retrieve account details with recent transactions")
    void testGetAccountDetails() {
        String accountId = "acct-details-001";

        TransactionRequest request = new TransactionRequest();
        request.setEventId("evt-details-001");
        request.setType(TransactionType.CREDIT);
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency("USD");

        accountService.applyTransaction(accountId, request);

        var details = accountService.getAccountDetails(accountId);
        assertNotNull(details);
        assertEquals(accountId, details.getAccountId());
        assertNotNull(details.getBalance());
        assertEquals(1, details.getRecentTransactions().size());
        assertEquals("evt-details-001", details.getRecentTransactions().get(0).getEventId());
    }

    @Test
    @DisplayName("Should handle multiple transactions for same account correctly")
    void testMultipleTransactionsForAccount() {
        String accountId = "acct-multiple-001";

        // Transaction 1
        TransactionRequest request1 = new TransactionRequest();
        request1.setEventId("evt-multi-001");
        request1.setType(TransactionType.CREDIT);
        request1.setAmount(new BigDecimal("100.00"));
        request1.setCurrency("USD");

        accountService.applyTransaction(accountId, request1);

        // Transaction 2
        TransactionRequest request2 = new TransactionRequest();
        request2.setEventId("evt-multi-002");
        request2.setType(TransactionType.DEBIT);
        request2.setAmount(new BigDecimal("25.00"));
        request2.setCurrency("USD");

        accountService.applyTransaction(accountId, request2);

        // Transaction 3
        TransactionRequest request3 = new TransactionRequest();
        request3.setEventId("evt-multi-003");
        request3.setType(TransactionType.CREDIT);
        request3.setAmount(new BigDecimal("50.00"));
        request3.setCurrency("USD");

        accountService.applyTransaction(accountId, request3);

        // Verify balance: 100 - 25 + 50 = 125
        var balance = accountService.getBalance(accountId);
        assertEquals(0, balance.getBalance().compareTo(new BigDecimal("125.00")));
        assertEquals(3, balance.getTransactionCount());
    }

    @Test
    @DisplayName("Should handle DEBIT transactions correctly")
    void testDebitTransaction() {
        String accountId = "acct-debit-001";

        // First apply credit
        TransactionRequest creditRequest = new TransactionRequest();
        creditRequest.setEventId("evt-debit-credit-001");
        creditRequest.setType(TransactionType.CREDIT);
        creditRequest.setAmount(new BigDecimal("100.00"));
        creditRequest.setCurrency("USD");

        accountService.applyTransaction(accountId, creditRequest);

        // Then apply debit
        TransactionRequest debitRequest = new TransactionRequest();
        debitRequest.setEventId("evt-debit-debit-001");
        debitRequest.setType(TransactionType.DEBIT);
        debitRequest.setAmount(new BigDecimal("40.00"));
        debitRequest.setCurrency("USD");

        var response = accountService.applyTransaction(accountId, debitRequest);
        assertNotNull(response);
        assertEquals(TransactionType.DEBIT, response.getType());
        assertEquals(new BigDecimal("40.00"), response.getAmount());
    }

    @Test
    @DisplayName("Should limit recent transactions to 20")
    void testAccountDetailsLimitsRecentTransactions() {
        String accountId = "acct-limit-001";

        // Submit 25 transactions
        for (int i = 1; i <= 25; i++) {
            TransactionRequest request = new TransactionRequest();
            request.setEventId("evt-limit-" + i);
            request.setType(TransactionType.CREDIT);
            request.setAmount(new BigDecimal("10.00"));
            request.setCurrency("USD");

            accountService.applyTransaction(accountId, request);
        }

        // Get account details and verify only 20 most recent are returned
        var details = accountService.getAccountDetails(accountId);
        assertEquals(20, details.getRecentTransactions().size());
    }

    @Test
    @DisplayName("Should maintain correct balance regardless of transaction order")
    void testBalanceIndependentOfOrder() {
        String accountId1 = "acct-order-1";
        String accountId2 = "acct-order-2";

        // Account 1: Submit in normal order
        TransactionRequest r1_1 = new TransactionRequest();
        r1_1.setEventId("evt-order1-1");
        r1_1.setType(TransactionType.CREDIT);
        r1_1.setAmount(new BigDecimal("100.00"));
        r1_1.setCurrency("USD");
        accountService.applyTransaction(accountId1, r1_1);

        TransactionRequest r1_2 = new TransactionRequest();
        r1_2.setEventId("evt-order1-2");
        r1_2.setType(TransactionType.DEBIT);
        r1_2.setAmount(new BigDecimal("30.00"));
        r1_2.setCurrency("USD");
        accountService.applyTransaction(accountId1, r1_2);

        // Account 2: Submit in reverse order (simulated - transactions will have same result)
        TransactionRequest r2_1 = new TransactionRequest();
        r2_1.setEventId("evt-order2-1");
        r2_1.setType(TransactionType.DEBIT);
        r2_1.setAmount(new BigDecimal("30.00"));
        r2_1.setCurrency("USD");
        accountService.applyTransaction(accountId2, r2_1);

        TransactionRequest r2_2 = new TransactionRequest();
        r2_2.setEventId("evt-order2-2");
        r2_2.setType(TransactionType.CREDIT);
        r2_2.setAmount(new BigDecimal("100.00"));
        r2_2.setCurrency("USD");
        accountService.applyTransaction(accountId2, r2_2);

        // Both should have same balance
        var balance1 = accountService.getBalance(accountId1);
        var balance2 = accountService.getBalance(accountId2);

        assertEquals(0, balance1.getBalance().compareTo(balance2.getBalance()));
        assertEquals(0, balance1.getBalance().compareTo(new BigDecimal("70.00")));
    }
}
