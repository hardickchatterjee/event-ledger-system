package com.example.eventsService.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class EventResponse {
    private String eventId;
    private String accountId;
    private TransactionType type;
    private BigDecimal amount;
    private String currency;
    private Instant eventTimestamp;
    private Map<String, String> metadata;
    private EventStatus status;
    private Instant ingestedAt;
}
