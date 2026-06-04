package com.example.accountService.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class BalanceResponse {
    private String accountId;
    private BigDecimal balance;
    private String currency;
    private long transactionCount;
}
