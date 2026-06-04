package com.example.eventsService.client;

import com.example.eventsService.model.TransactionType;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class TransactionRequest {
    private String eventId;
    private TransactionType type;
    private BigDecimal amount;
    private String currency;
}
