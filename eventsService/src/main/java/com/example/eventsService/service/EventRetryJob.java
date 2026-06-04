package com.example.eventsService.service;

import com.example.eventsService.client.AccountServiceClient;
import com.example.eventsService.client.TransactionRequest;
import com.example.eventsService.model.EventRecord;
import com.example.eventsService.model.EventStatus;
import com.example.eventsService.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "event.retry.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class EventRetryJob {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;

    @Value("${event.retry.max-attempts:3}")
    private Integer maxRetries;

    @Scheduled(fixedDelayString = "${event.retry.interval-minutes:5}", timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    @Transactional
    public void retryPendingEvents() {
        log.info("Starting async retry job for PENDING events");

        List<EventRecord> pendingEvents = eventRepository.findRetryableEvents(EventStatus.PENDING, maxRetries);

        if (pendingEvents.isEmpty()) {
            log.debug("No retryable PENDING events found");
            return;
        }

        log.info("Found {} retryable PENDING events", pendingEvents.size());

        for (EventRecord event : pendingEvents) {
            try {
                retryEvent(event);
            } catch (Exception ex) {
                log.error("Unexpected error retrying eventId={}: {}", event.getEventId(), ex.getMessage(), ex);
            }
        }

        log.info("Async retry job completed. Processed {} events", pendingEvents.size());
    }

    @Transactional
    protected void retryEvent(EventRecord event) {
        try {
            log.debug("Retrying eventId={}, attempt {}/{}", event.getEventId(), event.getRetryCount() + 1, maxRetries);

            TransactionRequest txRequest = TransactionRequest.builder()
                .eventId(event.getEventId())
                .type(event.getType())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .build();

            boolean accepted = accountServiceClient.applyTransaction(event.getAccountId(), txRequest);

            event.setLastRetryTime(Instant.now());
            event.setRetryCount(event.getRetryCount() + 1);

            if (accepted) {
                event.setStatus(EventStatus.PROCESSED);
                log.info("Successfully processed retried eventId={} after {} attempts", event.getEventId(), event.getRetryCount());
            } else {
                log.warn("Account Service unavailable for retried eventId={}. Will retry again.", event.getEventId());
            }

            eventRepository.save(event);

        } catch (Exception ex) {
            event.setLastRetryTime(Instant.now());
            event.setRetryCount(event.getRetryCount() + 1);

            if (event.getRetryCount() >= maxRetries) {
                event.setStatus(EventStatus.FAILED);
                log.error("Max retries exceeded for eventId={}, marking as FAILED", event.getEventId());
            } else {
                log.warn("Error retrying eventId={} (attempt {}/{}): {}",
                    event.getEventId(), event.getRetryCount(), maxRetries, ex.getMessage());
            }

            eventRepository.save(event);
        }
    }
}
