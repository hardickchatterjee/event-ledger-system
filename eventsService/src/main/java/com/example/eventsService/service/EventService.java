package com.example.eventsService.service;

import com.example.eventsService.client.AccountServiceClient;
import com.example.eventsService.client.TransactionRequest;
import com.example.eventsService.model.EventRecord;
import com.example.eventsService.model.EventRequest;
import com.example.eventsService.model.EventStatus;
import com.example.eventsService.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;

    @Transactional
    public EventRecord submitEvent(EventRequest request) {
        Optional<EventRecord> existing = eventRepository.findById(request.getEventId());
        if (existing.isPresent()) {
            log.info("Duplicate submission for eventId={}, returning existing record", request.getEventId());
            return existing.get();
        }

        EventStatus status;
        try {
            TransactionRequest txRequest = TransactionRequest.builder()
                .eventId(request.getEventId())
                .type(request.getType())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .build();

            boolean accepted = accountServiceClient.applyTransaction(
                request.getAccountId(), txRequest);

            status = accepted ? EventStatus.PROCESSED : EventStatus.PENDING;

        } catch (Exception ex) {
            log.error("Account Service returned error for eventId={}: {}", request.getEventId(), ex.getMessage());
            status = EventStatus.FAILED;
        }

        EventRecord record = EventRecord.builder()
            .eventId(request.getEventId())
            .accountId(request.getAccountId())
            .type(request.getType())
            .amount(request.getAmount())
            .currency(request.getCurrency())
            .eventTimestamp(request.getEventTimestamp())
            .metadata(request.getMetadata())
            .status(status)
            .retryCount(0)
            .build();

        return eventRepository.save(record);
    }

    public Optional<EventRecord> getEvent(String eventId) {
        return eventRepository.findById(eventId);
    }

    public List<EventRecord> getEventsByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId);
    }
}
