package com.example.accountService.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class TransactionResponse {
    private UUID transactionId;
    private String eventId;
    private String accountId;
    private TransactionType type;
    private BigDecimal amount;
    private String currency;
    private Instant appliedAt;
}
