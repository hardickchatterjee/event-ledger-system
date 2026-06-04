package com.example.eventsService.controller;

import com.example.eventsService.model.EventRequest;
import com.example.eventsService.model.TransactionType;
import com.example.eventsService.service.EventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("EventController Integration Tests")
class EventControllerIntegrationTest {

    @Autowired
    private EventService eventService;

    @Test
    @DisplayName("Should submit event successfully")
    void testSubmitEventSuccess() {
        EventRequest request = new EventRequest();
        request.setEventId("evt-integration-001");
        request.setAccountId("acct-int-001");
        request.setType(TransactionType.CREDIT);
        request.setAmount(new BigDecimal("100.50"));
        request.setCurrency("USD");
        request.setEventTimestamp(Instant.now());

        var result = eventService.submitEvent(request);

        assertNotNull(result);
        assertEquals("evt-integration-001", result.getEventId());
        assertEquals("acct-int-001", result.getAccountId());
        assertEquals(TransactionType.CREDIT, result.getType());
    }

    @Test
    @DisplayName("Should return existing event for duplicate submission (idempotency)")
    void testSubmitEventIdempotency() {
        EventRequest request = new EventRequest();
        request.setEventId("evt-idempotent-001");
        request.setAccountId("acct-idempotent-001");
        request.setType(TransactionType.CREDIT);
        request.setAmount(new BigDecimal("50.00"));
        request.setCurrency("USD");
        request.setEventTimestamp(Instant.now());

        var result1 = eventService.submitEvent(request);
        var result2 = eventService.submitEvent(request);

        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(result1.getEventId(), result2.getEventId());
    }

    @Test
    @DisplayName("Should retrieve event by ID")
    void testGetEventById() {
        EventRequest request = new EventRequest();
        request.setEventId("evt-get-001");
        request.setAccountId("acct-get-001");
        request.setType(TransactionType.CREDIT);
        request.setAmount(new BigDecimal("75.00"));
        request.setCurrency("USD");
        request.setEventTimestamp(Instant.now());

        eventService.submitEvent(request);

        var result = eventService.getEvent("evt-get-001");

        assertTrue(result.isPresent());
        assertEquals("evt-get-001", result.get().getEventId());
    }

    @Test
    @DisplayName("Should return empty for non-existent event")
    void testGetEventNotFound() {
        var result = eventService.getEvent("nonexistent-event-id");
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("Should retrieve all events for account ordered by timestamp")
    void testGetEventsByAccount() {
        String accountId = "acct-order-001";
        Instant now = Instant.now();

        EventRequest request1 = new EventRequest();
        request1.setEventId("evt-order-001");
        request1.setAccountId(accountId);
        request1.setType(TransactionType.CREDIT);
        request1.setAmount(new BigDecimal("100.00"));
        request1.setCurrency("USD");
        request1.setEventTimestamp(now.minusSeconds(10));

        EventRequest request2 = new EventRequest();
        request2.setEventId("evt-order-002");
        request2.setAccountId(accountId);
        request2.setType(TransactionType.DEBIT);
        request2.setAmount(new BigDecimal("30.00"));
        request2.setCurrency("USD");
        request2.setEventTimestamp(now);

        eventService.submitEvent(request1);
        eventService.submitEvent(request2);

        var results = eventService.getEventsByAccount(accountId);

        assertEquals(2, results.size());
        assertEquals("evt-order-001", results.get(0).getEventId());
        assertEquals("evt-order-002", results.get(1).getEventId());
    }

    @Test
    @DisplayName("Should return empty list for account with no events")
    void testGetEventsByAccountEmpty() {
        var results = eventService.getEventsByAccount("acct-nonexistent-001");
        assertEquals(0, results.size());
    }

    @Test
    @DisplayName("Should handle out-of-order event submissions correctly")
    void testOutOfOrderEventSubmission() {
        String accountId = "acct-outoforder-001";
        Instant baseTime = Instant.parse("2026-05-15T12:00:00Z");

        // Submit "later" event first
        EventRequest laterEvent = new EventRequest();
        laterEvent.setEventId("evt-later-001");
        laterEvent.setAccountId(accountId);
        laterEvent.setType(TransactionType.CREDIT);
        laterEvent.setAmount(new BigDecimal("100.00"));
        laterEvent.setCurrency("USD");
        laterEvent.setEventTimestamp(baseTime.plusSeconds(60));

        eventService.submitEvent(laterEvent);

        // Submit "earlier" event second
        EventRequest earlierEvent = new EventRequest();
        earlierEvent.setEventId("evt-earlier-001");
        earlierEvent.setAccountId(accountId);
        earlierEvent.setType(TransactionType.DEBIT);
        earlierEvent.setAmount(new BigDecimal("30.00"));
        earlierEvent.setCurrency("USD");
        earlierEvent.setEventTimestamp(baseTime);

        eventService.submitEvent(earlierEvent);

        // Retrieve events and verify they're ordered by eventTimestamp
        var results = eventService.getEventsByAccount(accountId);

        assertEquals(2, results.size());
        assertEquals("evt-earlier-001", results.get(0).getEventId());
        assertEquals("evt-later-001", results.get(1).getEventId());
        assertTrue(results.get(0).getEventTimestamp().isBefore(results.get(1).getEventTimestamp()));
    }
}
