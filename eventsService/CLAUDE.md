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

### 3. Graceful Degradation with Circuit Breaker

If the Account Service is unavailable:

- `AccountServiceClient.applyTransaction()` is wrapped with Resilience4j `@CircuitBreaker`
- **Network failures** (`ResourceAccessException`) → circuit opens after threshold (50% failure rate in 10-call window)
- **Circuit open** → `CallNotPermittedException` → 503 Service Unavailable
- Event is **NOT saved** to prevent data loss on replay
- Client receives clear 503 error with retry guidance

See: `EventController.submitEvent()` — always returns 200 OK for PROCESSED, 503 for unavailability.
See: `GlobalExceptionHandler` — handles `CallNotPermittedException`, `ResourceAccessException`, `RestClientResponseException`

### 4. Distributed Tracing & Observability

All requests are traced end-to-end via OpenTelemetry:

- Trace IDs injected into W3C `traceparent` headers
- All logs include `trace.id` and `span.id` in ECS JSON format
- Traces exported to OTel Collector → Zipkin for visualization
- Custom metrics: `events.submitted` counter (tagged by status)

See: `application.properties` — `management.otlp.tracing.endpoint`, `logging.structured.format.console=ecs`

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
│   └── AccountServiceClient.java     @Component, uses RestClient + @CircuitBreaker
├── config/
│   └── RestClientConfig.java         Provides RestClient bean with 5s timeout
├── service/
│   └── EventService.java             Business logic: idempotency, metrics, tracing
├── controller/
│   ├── EventController.java          REST endpoints for event submission & queries
│   └── HealthController.java         Custom /health-check endpoint
└── exception/
    └── GlobalExceptionHandler.java   @RestControllerAdvice + 503 error handling
```

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/events` | Submit transaction event (200 OK or 503 Service Unavailable) |
| GET | `/events/{id}` | Retrieve single event by eventId |
| GET | `/events?account={accountId}` | List events for account (ordered by eventTimestamp ASC) |
| GET | `/health-check` | Custom service health (database connectivity) |
| GET | `/actuator/health` | Spring Actuator health with details |
| GET | `/actuator/prometheus` | Prometheus metrics endpoint |

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

**Role:** HTTP client to Account Service (port 8081) with circuit breaker protection.

**applyTransaction(accountId, request) → boolean:**
- Wrapped with `@CircuitBreaker(name = "accountService")`
- Returns `true` if Account Service accepted the transaction (200 OK)
- Throws `CallNotPermittedException` if circuit is OPEN (after 50% failure rate in 10-call window)
- Throws `ResourceAccessException` on network failures (connection refused, 5s timeout)
- Throws `RestClientResponseException` on 4xx/5xx errors

Uses Spring Boot 4's auto-configured `RestClient` with 5-second connect/read timeout.

**Circuit Breaker Config:**
- Window size: 10 calls
- Failure threshold: 50%
- Wait in open state: 30 seconds
- Half-open: 3 permitted calls before closing

### EventController

**HTTP Semantics:**
- **POST /events**: 
  - 200 OK if Account Service accepts (status=PROCESSED)
  - 503 Service Unavailable if Account Service is unreachable/unavailable
  - 400 Bad Request for validation errors
  - Exception auto-propagates via `GlobalExceptionHandler`
- **GET /events/{id}**: Returns 200 or 404
- **GET /events?account=...**: Returns 200 with list (may be empty)

Converts `EventRecord` → `EventResponse` DTOs for serialization.

All requests are traced and logged with structured JSON (ECS format) including `trace.id` and `span.id`.

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
<!-- Web & Data -->
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

<!-- Observability -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Resiliency -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-aop</artifactId>
</dependency>
<dependency>
    <groupId>org.aspectj</groupId>
    <artifactId>aspectjweaver</artifactId>
</dependency>

<!-- Database & Serialization -->
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

**Notes:**
- Spring Boot 4.0.6 requires explicit `spring-boot-starter-restclient` (not included in webmvc)
- `spring-boot-starter-opentelemetry` includes OTel SDK, OTLP exporter, and Micrometer Tracing auto-config
- `resilience4j-spring-boot3` v2.2.0 is compatible with Spring Boot 4 (uses same AOP layer)

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

# Observability
logging.structured.format.console=ecs
management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.health.show-details=always
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces
management.tracing.sampling.probability=1.0

# RestClient timeout (5s each)
spring.http.client.factory=simple
spring.http.client.connect-timeout=5s
spring.http.client.read-timeout=5s

# Resilience4j circuit breaker
resilience4j.circuitbreaker.instances.accountService.sliding-window-type=COUNT_BASED
resilience4j.circuitbreaker.instances.accountService.sliding-window-size=10
resilience4j.circuitbreaker.instances.accountService.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.accountService.wait-duration-in-open-state=30s
resilience4j.circuitbreaker.instances.accountService.permitted-number-of-calls-in-half-open-state=3
resilience4j.circuitbreaker.instances.accountService.automatic-transition-from-open-to-half-open-enabled=true
```

**Key configurations:**
- `logging.structured.format.console=ecs` — JSON logs in ECS format with trace context
- `management.otlp.tracing.endpoint` — OTel Collector HTTP endpoint (override via `MANAGEMENT_OTLP_TRACING_ENDPOINT` env var in Docker)
- `spring.http.client.*` — RestClient timeout settings
- Resilience4j circuit breaker: opens after 50% failure in 10-call window, stays open 30 seconds

## Testing the System

### Start via Docker Compose (Recommended)

```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system
docker-compose up -d
```

This starts:
- **eventsService** (port 8080)
- **accountService** (port 8081)
- **Zipkin** (port 9411) — trace visualization
- **OTel Collector** (ports 4317/4318) — trace collection
- **Prometheus** (port 9090) — metrics visualization

### Start Locally (Development)

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

## Observability Features

### Structured JSON Logging
- All logs output in ECS format with `@timestamp`, `service.name`, `log.level`, `trace.id`, `span.id`
- Enabled via `logging.structured.format.console=ecs`
- Useful for log aggregation (ELK, Splunk, Datadog)

### Distributed Tracing
- OpenTelemetry auto-instruments HTTP calls, database queries, thread context
- W3C `traceparent` headers propagated to Account Service
- Traces exported to OTel Collector (OTLP HTTP) → Zipkin UI
- Sample rate: 100% (configurable via `management.tracing.sampling.probability`)

### Metrics
- Prometheus endpoint: `GET /actuator/prometheus`
- Custom counters:
  - `events.submitted_total{status=...}` — PROCESSED, DUPLICATE
  - `transactions.applied_total{status=...}` — ACCEPTED, DUPLICATE
- Auto-instrumented: JVM, HTTP server/client, database

## Resiliency Features

### Circuit Breaker
- **Pattern:** Resilience4j `@CircuitBreaker` on `AccountServiceClient.applyTransaction()`
- **States:** CLOSED (normal) → OPEN (failing) → HALF_OPEN (recovery) → CLOSED
- **Config:** 50% failure rate threshold, 10-call window, 30s wait in open state
- **Behavior:** 
  - Circuit CLOSED: normal operation
  - Circuit OPEN: `CallNotPermittedException` → 503 response
  - Circuit HALF_OPEN: test 3 calls; if success, close; if fail, reopen

### Timeout
- RestClient timeout: 5 seconds (connect + read)
- Prevents hanging on slow Account Service
- Configured via Spring properties: `spring.http.client.*`

### Error Handling
- Network failures → 503 Service Unavailable
- Circuit breaker open → 503 with "Circuit Open" message
- Account Service errors → 503 with guidance
- Events NOT saved on failure (prevents duplicate processing on retry)

## Common Issues

**Q: RestClient.Builder not found**
A: Ensure `spring-boot-starter-restclient` is in pom.xml. Spring Boot 4.0.6 requires it explicitly.

**Q: H2 dialect warning**
A: Harmless; Hibernate auto-detects H2. Can be removed by deleting `spring.jpa.database-platform`.

**Q: Account Service unavailable → what happens?**
A: Gateway returns 503 Service Unavailable. Event is NOT saved. Client should retry. After 50% failures in 10 calls, circuit opens for 30s (fast-fail subsequent requests).

**Q: No traces in Zipkin**
A: Verify OTel Collector is running and reachable. Check logs for `Publishing metrics...` message. Sampling probability defaults to 100%.

**Q: Prometheus metrics empty**
A: Metrics are cumulative. Submit a few events, then query `/actuator/prometheus`. Look for `events_submitted_total` (with underscore, Prometheus naming convention).

**Q: Can I scale to multiple services?**
A: This is a reference implementation. For production:
- Use persistent databases (PostgreSQL, etc.) instead of H2 in-memory
- Add a message queue (Kafka, RabbitMQ) for async event processing
- Implement explicit retry logic for PENDING events (if stored)
- Add load balancer for multiple gateway instances
- Configure distributed tracing properly across replicas

---

**Last Updated:** 2026-06-04
**Observability Updated:** 2026-06-04 — Added OTel, Prometheus, structured logging, circuit breaker
