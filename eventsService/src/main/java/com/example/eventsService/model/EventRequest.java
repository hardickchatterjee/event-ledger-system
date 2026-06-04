package com.example.eventsService.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Data
public class EventRequest {

    @NotBlank(message = "eventId is required")
    private String eventId;

    @NotBlank(message = "accountId is required")
    private String accountId;

    @NotNull(message = "type is required")
    private TransactionType type;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be a 3-character ISO code")
    private String currency;

    @NotNull(message = "eventTimestamp is required")
    private Instant eventTimestamp;

    private Map<String, String> metadata;
}
