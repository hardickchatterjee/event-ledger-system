package com.example.eventsService.service;

import com.example.eventsService.client.AccountServiceClient;
import com.example.eventsService.client.TransactionRequest;
import com.example.eventsService.model.EventRecord;
import com.example.eventsService.model.EventRequest;
import com.example.eventsService.model.EventStatus;
import com.example.eventsService.model.TransactionType;
import com.example.eventsService.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventService Unit Tests")
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    @InjectMocks
    private EventService eventService;

    private EventRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new EventRequest();
        validRequest.setEventId("evt-001");
        validRequest.setAccountId("acct-123");
        validRequest.setType(TransactionType.CREDIT);
        validRequest.setAmount(new BigDecimal("100.50"));
        validRequest.setCurrency("USD");
        validRequest.setEventTimestamp(Instant.now());
    }

    @Test
    @DisplayName("Should process new event successfully when Account Service accepts")
    void testSubmitEventNewEventSuccess() {
        when(eventRepository.findById("evt-001")).thenReturn(Optional.empty());
        when(accountServiceClient.applyTransaction(eq("acct-123"), any(TransactionRequest.class)))
            .thenReturn(true);

        EventRecord savedRecord = EventRecord.builder()
            .eventId("evt-001")
            .accountId("acct-123")
            .type(TransactionType.CREDIT)
            .amount(new BigDecimal("100.50"))
            .currency("USD")
            .eventTimestamp(validRequest.getEventTimestamp())
            .status(EventStatus.PROCESSED)
            .retryCount(0)
            .build();

        when(eventRepository.save(any(EventRecord.class))).thenReturn(savedRecord);

        EventRecord result = eventService.submitEvent(validRequest);

        assertNotNull(result);
        assertEquals("evt-001", result.getEventId());
        assertEquals("acct-123", result.getAccountId());
        assertEquals(EventStatus.PROCESSED, result.getStatus());
        verify(accountServiceClient, times(1)).applyTransaction(eq("acct-123"), any());
        verify(eventRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("Should return existing event for duplicate submission (idempotency)")
    void testSubmitEventIdempotency() {
        EventRecord existingRecord = EventRecord.builder()
            .eventId("evt-001")
            .accountId("acct-123")
            .type(TransactionType.CREDIT)
            .amount(new BigDecimal("100.50"))
            .currency("USD")
            .eventTimestamp(validRequest.getEventTimestamp())
            .status(EventStatus.PROCESSED)
            .retryCount(0)
            .build();

        when(eventRepository.findById("evt-001")).thenReturn(Optional.of(existingRecord));

        EventRecord result = eventService.submitEvent(validRequest);

        assertNotNull(result);
        assertEquals("evt-001", result.getEventId());
        assertEquals(EventStatus.PROCESSED, result.getStatus());
        // Should NOT call Account Service on duplicate
        verify(accountServiceClient, never()).applyTransaction(any(), any());
        // Should NOT save to repository on duplicate
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should mark event as FAILED when Account Service throws exception")
    void testSubmitEventAccountServiceFailure() {
        when(eventRepository.findById("evt-001")).thenReturn(Optional.empty());
        when(accountServiceClient.applyTransaction(eq("acct-123"), any(TransactionRequest.class)))
            .thenThrow(new RuntimeException("Service unavailable"));

        EventRecord savedRecord = EventRecord.builder()
            .eventId("evt-001")
            .accountId("acct-123")
            .type(TransactionType.CREDIT)
            .amount(new BigDecimal("100.50"))
            .currency("USD")
            .eventTimestamp(validRequest.getEventTimestamp())
            .status(EventStatus.FAILED)
            .retryCount(0)
            .build();

        when(eventRepository.save(any(EventRecord.class))).thenReturn(savedRecord);

        EventRecord result = eventService.submitEvent(validRequest);

        assertNotNull(result);
        assertEquals(EventStatus.FAILED, result.getStatus());
        verify(eventRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("Should set status to PENDING when Account Service returns false")
    void testSubmitEventAccountServicePending() {
        when(eventRepository.findById("evt-001")).thenReturn(Optional.empty());
        when(accountServiceClient.applyTransaction(eq("acct-123"), any(TransactionRequest.class)))
            .thenReturn(false);

        EventRecord savedRecord = EventRecord.builder()
            .eventId("evt-001")
            .accountId("acct-123")
            .type(TransactionType.CREDIT)
            .amount(new BigDecimal("100.50"))
            .currency("USD")
            .eventTimestamp(validRequest.getEventTimestamp())
            .status(EventStatus.PENDING)
            .retryCount(0)
            .build();

        when(eventRepository.save(any(EventRecord.class))).thenReturn(savedRecord);

        EventRecord result = eventService.submitEvent(validRequest);

        assertNotNull(result);
        assertEquals(EventStatus.PENDING, result.getStatus());
    }

    @Test
    @DisplayName("Should retrieve single event by ID")
    void testGetEvent() {
        EventRecord record = EventRecord.builder()
            .eventId("evt-001")
            .accountId("acct-123")
            .status(EventStatus.PROCESSED)
            .build();

        when(eventRepository.findById("evt-001")).thenReturn(Optional.of(record));

        Optional<EventRecord> result = eventService.getEvent("evt-001");

        assertTrue(result.isPresent());
        assertEquals("evt-001", result.get().getEventId());
    }

    @Test
    @DisplayName("Should return empty Optional when event not found")
    void testGetEventNotFound() {
        when(eventRepository.findById("nonexistent")).thenReturn(Optional.empty());

        Optional<EventRecord> result = eventService.getEvent("nonexistent");

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("Should retrieve all events for account ordered by timestamp")
    void testGetEventsByAccount() {
        EventRecord record1 = EventRecord.builder()
            .eventId("evt-001")
            .accountId("acct-123")
            .eventTimestamp(Instant.parse("2026-05-15T09:00:00Z"))
            .build();

        EventRecord record2 = EventRecord.builder()
            .eventId("evt-002")
            .accountId("acct-123")
            .eventTimestamp(Instant.parse("2026-05-15T10:00:00Z"))
            .build();

        when(eventRepository.findByAccountIdOrderByEventTimestampAsc("acct-123"))
            .thenReturn(java.util.List.of(record1, record2));

        java.util.List<EventRecord> results = eventService.getEventsByAccount("acct-123");

        assertEquals(2, results.size());
        assertEquals("evt-001", results.get(0).getEventId());
        assertEquals("evt-002", results.get(1).getEventId());
    }

    @Test
    @DisplayName("Should handle out-of-order events correctly")
    void testOutOfOrderEventHandling() {
        // Event with later timestamp submitted after event with earlier timestamp
        EventRequest earlyEvent = new EventRequest();
        earlyEvent.setEventId("evt-001");
        earlyEvent.setAccountId("acct-123");
        earlyEvent.setType(TransactionType.CREDIT);
        earlyEvent.setAmount(new BigDecimal("100.00"));
        earlyEvent.setCurrency("USD");
        earlyEvent.setEventTimestamp(Instant.parse("2026-05-15T10:00:00Z"));

        EventRequest lateEvent = new EventRequest();
        lateEvent.setEventId("evt-002");
        lateEvent.setAccountId("acct-123");
        lateEvent.setType(TransactionType.DEBIT);
        lateEvent.setAmount(new BigDecimal("50.00"));
        lateEvent.setCurrency("USD");
        lateEvent.setEventTimestamp(Instant.parse("2026-05-15T09:00:00Z"));

        // Both should be stored with their respective timestamps
        assertNotEquals(earlyEvent.getEventTimestamp(), lateEvent.getEventTimestamp());
        assertTrue(lateEvent.getEventTimestamp().isBefore(earlyEvent.getEventTimestamp()));
    }
}
