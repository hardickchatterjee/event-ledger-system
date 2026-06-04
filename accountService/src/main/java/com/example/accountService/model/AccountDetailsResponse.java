package com.example.accountService.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class AccountDetailsResponse {
    private String accountId;
    private Instant createdAt;
    private BigDecimal balance;
    private List<TransactionResponse> recentTransactions;
}
