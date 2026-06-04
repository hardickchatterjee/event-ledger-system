# Event Ledger System — eventsService (Event Gateway API)

## Overview

The **Event Gateway API** is the public-facing entry point for the Event Ledger system. It receives financial transaction events from upstream systems, validates and deduplicates them, stores event records, and forwards transactions to the internal **Account Service** (port 8081) for balance updates.

## Architecture

- **Port:** 8080 (HTTP)
- **Database:** H2 in-memory (`eventsdb`)
- **Framework:** Spring Boot 4.0.6, Spring MVC, Spring Data JPA
- **Language:** Java 21

## Key Design Patterns

### 1. Idempotency via Natural Primary Key

The `eventId` (client-provided unique identifier) is the JPA `@Id` on `EventRecord`. This guarantees:

- **First insertion:** Saves the record; calls Account Service
- **Duplicate submission:** `findById(eventId)` returns existing record immediately; **no second Account Service call**

See: `EventService.submitEvent()` — the `findById` check happens before any processing.

### 2. Out-of-Order Event Handling

Events may arrive with earlier timestamps than previously stored events. The system handles this by:

- Storing `eventTimestamp` (when the event *actually occurred*, not when it arrived)
- **Listing events** via `findByAccountIdOrderByEventTimestampAsc()` — returns events in business time, not ingestion order
- **Balance computation** happens at query time, so insertion order is irrelevant

### 3. Graceful Degradation

If the Account Service is unavailable:

- `AccountServiceClient.applyTransaction()` catches `ResourceAccessException` (network-level failures) and returns `false`
- Event status is set to `PENDING` instead of `PROCESSED`
- Gateway returns **202 ACCEPTED** (not 5xx) to the client
- Event is stored locally for later retry/processing

See: `EventController.submitEvent()` — returns 202 for PENDING, 200 for PROCESSED/FAILED.

## File Structure

```
src/main/java/com/example/eventsService/
├── model/
│   ├── TransactionType.java          Enum: CREDIT, DEBIT
│   ├── EventStatus.java              Enum: PROCESSED, PENDING, FAILED
│   ├── EventRecord.java              @Entity, PK = eventId
│   ├── EventRequest.java             DTO for incoming requests (validated)
│   ├── EventResponse.java            DTO for responses
│   └── MapToJsonConverter.java       Custom JPA converter for metadata Map→JSON
├── repository/
│   └── EventRepository.java          JpaRepository<EventRecord, String>
├── client/
│   ├── TransactionRequest.java       DTO for Account Service calls
│   └── AccountServiceClient.java     @Component, uses RestClient
├── config/
│   └── RestClientConfig.java         Provides RestClient bean
├── service/
│   └── EventService.java             Business logic: idempotency, account service calls
├── controller/
│   └── EventController.java          4 REST endpoints
└── exception/
    └── GlobalExceptionHandler.java   @RestControllerAdvice for error responses
```

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/events` | Submit transaction event |
| GET | `/events/{id}` | Retrieve single event by eventId |
| GET | `/events?account={accountId}` | List events for account (ordered by eventTimestamp ASC) |
| GET | `/actuator/health` | Health check |

## EventRecord Entity

| Field | Type | Notes |
|-------|------|-------|
| `eventId` | String | `@Id` — natural PK, idempotency key |
| `accountId` | String | Account receiving the transaction |
| `type` | TransactionType | CREDIT or DEBIT |
| `amount` | BigDecimal | Always > 0; sign determined by type |
| `currency` | String | ISO 4217 code (e.g., USD) |
| `eventTimestamp` | Instant | Business time — when event occurred |
| `metadata` | Map<String,String> | Optional; stored as JSON via `MapToJsonConverter` |
| `status` | EventStatus | PROCESSED, PENDING, or FAILED |
| `ingestedAt` | Instant | Set in `@PrePersist`; NOT used for ordering |

## Key Classes

### EventService

**Role:** Business logic for event submission.

**submitEvent(EventRequest) → EventRecord:**
1. Check `eventRepository.findById(eventId)` — if exists, return immediately (idempotent)
2. Call `accountServiceClient.applyTransaction(...)`:
   - `true` → status = PROCESSED
   - `false` → status = PENDING (service unavailable)
   - Exception → status = FAILED
3. Save `EventRecord` and return

**Critical:** The `findById` check must happen *before* calling Account Service to prevent duplicate calls on replay.

### AccountServiceClient

**Role:** HTTP client to Account Service (port 8081).

**applyTransaction(accountId, request) → boolean:**
- Returns `true` if Account Service accepted the transaction (200 OK)
- Returns `false` if `ResourceAccessException` (connection refused, timeout) — triggers PENDING status
- Throws other exceptions (4xx/5xx from Account Service) — triggers FAILED status

Uses Spring Boot 4's auto-configured `RestClient` for synchronous REST calls.

### EventController

**HTTP Semantics:**
- **POST /events**: Returns 200 OK for PROCESSED/FAILED, 202 ACCEPTED for PENDING
- **GET /events/{id}**: Returns 200 or 404
- **GET /events?account=...**: Returns 200 with list (may be empty)

Converts `EventRecord` → `EventResponse` DTOs for serialization.

## Database Schema

Created by Hibernate (ddl-auto=create-drop):

```sql
CREATE TABLE event_records (
    event_id VARCHAR(255) PRIMARY KEY NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    type ENUM('CREDIT','DEBIT') NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    metadata TEXT,
    status ENUM('PROCESSED','PENDING','FAILED') NOT NULL,
    ingested_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

## Dependencies (pom.xml Highlights)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-restclient</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
</dependency>
```

**Note:** Spring Boot 4.0.6 requires explicit `spring-boot-starter-restclient` for `RestClient.Builder` auto-configuration. The plan mentioned it would come from webmvc, but it doesn't in Boot 4.

## Jakarta EE Namespaces

All imports use `jakarta.*` (not `javax.*`):
- `jakarta.persistence.*` — JPA annotations
- `jakarta.validation.constraints.*` — Bean Validation
- `jakarta.servlet.*` — Servlet API
- `jakarta.transaction.*` — Transaction annotations

Spring Boot 4 = Spring Framework 7 = Jakarta EE 11.

## Configuration (application.properties)

```properties
spring.application.name=eventsService
server.port=8080

spring.datasource.url=jdbc:h2:mem:eventsdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true

spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

account.service.base-url=http://localhost:8081

management.endpoints.web.exposure.include=health,info
```

- `account.service.base-url` is injected into `AccountServiceClient` via `@Value`
- H2 console accessible at `/h2-console` for debugging

## Testing the System

### Start Both Services

```bash
# Terminal 1: eventsService
cd /Users/hardickchatterjee/Downloads/eventsService
./mvnw spring-boot:run

# Terminal 2: accountService
cd /Users/hardickchatterjee/Downloads/accountService
./mvnw spring-boot:run
```

### Submit Events

```bash
# CREDIT event
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":200.00,"currency":"USD","eventTimestamp":"2026-05-15T09:00:00Z"}'

# Same eventId again (idempotent)
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":200.00,"currency":"USD","eventTimestamp":"2026-05-15T09:00:00Z"}'
# Returns 200 OK, same response

# Out-of-order DEBIT
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-002","accountId":"acct-123","type":"DEBIT","amount":50.00,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}'
```

### Query Events and Accounts

```bash
# List events for account (ordered by eventTimestamp ASC)
curl "http://localhost:8080/events?account=acct-123"

# Get single event
curl http://localhost:8080/events/evt-001

# Get account balance (calls Account Service)
curl http://localhost:8081/accounts/acct-123/balance

# Get account details
curl http://localhost:8081/accounts/acct-123
```

## Common Issues

**Q: RestClient.Builder not found**
A: Ensure `spring-boot-starter-restclient` is in pom.xml dependencies. Spring Boot 4.0.6 requires it explicitly.

**Q: H2 dialect warning**
A: Harmless warning; Hibernate auto-detects H2. Can be removed by deleting `spring.jpa.database-platform=...` from properties.

**Q: Account Service unavailable → what happens?**
A: Gateway returns 202 ACCEPTED with `status=PENDING`. The event is stored; downstream processes (not in scope) would retry later. Gateway does not 5xx to client.

**Q: Can I scale to multiple services?**
A: This is a reference implementation. For production:
- Use persistent databases (PostgreSQL, etc.) instead of H2 in-memory
- Add a message queue (Kafka, RabbitMQ) for async event processing
- Implement explicit retry logic for PENDING events
- Add circuit breaker pattern for Account Service calls

---

**Last Updated:** 2026-06-04
