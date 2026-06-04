package com.example.eventsService.controller;

import com.example.eventsService.model.EventRecord;
import com.example.eventsService.model.EventRequest;
import com.example.eventsService.model.EventResponse;
import com.example.eventsService.model.EventStatus;
import com.example.eventsService.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    @RateLimiter(name = "eventSubmission", fallbackMethod = "submitEventFallback")
    public ResponseEntity<EventResponse> submitEvent(@Valid @RequestBody EventRequest request) {
        EventRecord record = eventService.submitEvent(request);
        EventResponse response = toResponse(record);

        HttpStatus status = record.getStatus() == EventStatus.PENDING
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;

        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable String id) {
        return eventService.getEvent(id)
            .map(record -> ResponseEntity.ok(toResponse(record)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<EventResponse>> getEventsByAccount(
            @RequestParam String account) {
        List<EventResponse> responses = eventService.getEventsByAccount(account)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    public ResponseEntity<String> submitEventFallback(@Valid @RequestBody EventRequest request, Exception ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body("Rate limit exceeded. Maximum 100 requests per minute allowed. Please retry later.");
    }

    private EventResponse toResponse(EventRecord record) {
        return EventResponse.builder()
            .eventId(record.getEventId())
            .accountId(record.getAccountId())
            .type(record.getType())
            .amount(record.getAmount())
            .currency(record.getCurrency())
            .eventTimestamp(record.getEventTimestamp())
            .metadata(record.getMetadata())
            .status(record.getStatus())
            .ingestedAt(record.getIngestedAt())
            .build();
    }
}
