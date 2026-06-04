# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Project Overview

**Event Ledger System** is a distributed, event-driven transaction processing system with two independent Spring Boot services:

1. **eventsService** (port 8080) — Public Event Gateway API
   - Receives and stores financial transaction events
   - Ensures idempotency via natural key (`eventId`)
   - Forwards transactions to Account Service with circuit breaker resilience
   - Handles out-of-order event arrivals via business-time ordering

2. **accountService** (port 8081) — Internal Account Service (called by eventsService)
   - Manages account state and balances
   - Deduplicates transactions by `eventId`
   - Auto-creates accounts on first transaction
   - Computes balances at query time (no stored balance field)

**Key Technologies:**
- Java 21, Spring Boot 4.0.6
- H2 in-memory databases (separate instances per service)
- OpenTelemetry + Zipkin for distributed tracing
- Prometheus for metrics
- Resilience4j circuit breaker (eventsService only)
- Jakarta EE 11 namespaces (`jakarta.*`, not `javax.*`)

---

## Build and Test Commands

### Building Individual Services

Each service has its own Maven wrapper. No root aggregator `pom.xml` exists.

```bash
# Build eventsService
cd eventsService
./mvnw clean package

# Build accountService
cd accountService
./mvnw clean package
```

### Running Tests

```bash
# Run all tests in eventsService
cd eventsService
./mvnw test

# Run a single test class
cd eventsService
./mvnw test -Dtest=EventServiceTest

# Run a single test method
cd eventsService
./mvnw test -Dtest=EventServiceTest#testIdempotency
```

### Running Services Locally

**Option 1: Terminal per service (development)**

```bash
# Terminal 1: eventsService
cd eventsService
./mvnw spring-boot:run

# Terminal 2: accountService
cd accountService
./mvnw spring-boot:run
```

Services will be available at:
- eventsService: http://localhost:8080
- accountService: http://localhost:8081

**Option 2: Docker Compose (with full observability stack)**

```bash
# From project root
docker-compose up -d
```

This starts:
- **eventsService** (port 8080) — with circuit breaker & tracing
- **accountService** (port 8081) — with distributed tracing
- **Zipkin** (port 9411) — trace visualization at http://localhost:9411
- **OTel Collector** (ports 4317/4318) — trace ingestion
- **Prometheus** (port 9090) — metrics at http://localhost:9090

---

## Architecture & Design Patterns

### Idempotency & Deduplication

**eventsService:**
- `eventId` (client-provided) is the `@Id` (natural PK) on `EventRecord`
- On duplicate submission: `findById(eventId)` returns existing record immediately, **no Account Service call**
- Prevents double-charging on network retries

**accountService:**
- `eventId` has a UNIQUE constraint on `Transaction` entity
- Application-level `existsByEventId` check + database-level constraint ensures single processing
- Catches race conditions gracefully via `DataIntegrityViolationException` handling

### Out-of-Order Event Handling

- Events stored with `eventTimestamp` (business time, not ingestion order)
- Queries order by `eventTimestamp ASC` to return events in logical sequence
- Balance computed as sum of all transactions → insertion order irrelevant
- System is safe for events arriving out of chronological order

### Resilience: Circuit Breaker Pattern

**eventsService → accountService communication** is wrapped with Resilience4j `@CircuitBreaker`:

- **Threshold:** 50% failure rate in 10-call window
- **Open state:** 30-second cooldown, then 3 test calls in half-open
- **Behavior:** Network failures or 5xx errors trigger circuit open
- **User impact:** Circuit open → 503 Service Unavailable with retry guidance
- **Data safety:** Event NOT saved until Account Service confirms (prevents replay issues)

See: `AccountServiceClient.applyTransaction()` annotation and `GlobalExceptionHandler` error mapping.

### Observability

**Structured Logging (ECS format):**
- All logs include `@timestamp`, `service.name`, `log.level`, `trace.id`, `span.id`
- Enabled via `logging.structured.format.console=ecs`
- Ready for log aggregation (ELK, Splunk, Datadog)

**Distributed Tracing (OpenTelemetry):**
- W3C `traceparent` headers propagate trace context across services
- HTTP, database, and thread context auto-instrumented
- Traces exported to OTel Collector → Zipkin (100% sample rate by default)

**Metrics (Prometheus):**
- Custom counters: `events.submitted_total`, `transactions.applied_total` (by status)
- Auto-instrumented: JVM, HTTP server/client, database
- Endpoint: `/actuator/prometheus`

### Request Flow

```
Client POST /events
  ↓
eventsService validates request
  ↓
Check findById(eventId) for idempotency
  ↓ (new event)
Call accountService:/accounts/{accountId}/transactions (with circuit breaker)
  ↓
accountService checks existsByEventId + auto-creates account
  ↓
accountService saves Transaction with UNIQUE eventId constraint
  ↓
eventsService saves EventRecord with status = PROCESSED
  ↓
Return 200 OK to client

(Circuit open or Account Service unavailable)
  ↓
Return 503 Service Unavailable (event NOT saved)
  ↓
Client retries (next attempt finds circuit still open for 30s, fast-fails)
```

---

## Service-Specific Architecture

Detailed architecture, endpoint documentation, and configuration for each service is in their own CLAUDE.md files:

- **[eventsService/CLAUDE.md](eventsService/CLAUDE.md)** — API Gateway design, circuit breaker, event storage, out-of-order handling
- **[accountService/CLAUDE.md](accountService/CLAUDE.md)** — Transaction processing, balance computation, deduplication, auto-account-creation

**Both files include:**
- Detailed class and method documentation
- Database schemas and entity relationships
- Full endpoint reference (methods, paths, status codes)
- Configuration properties and environment variable overrides
- Testing examples and curl commands
- Troubleshooting FAQ and scaling notes

---

## Project Structure

```
event-ledger-system/
├── CLAUDE.md (this file)
├── AGENTS.md (agent/future Claude instances guidance)
├── docker-compose.yml (local dev environment)
├── otel-collector-config.yaml (OpenTelemetry Collector config)
├── prometheus.yml (Prometheus scrape config)
├── .github/ (CI/CD, workflows)
├── .vscode/ (IDE settings)
│
├── eventsService/ (Port 8080 — public Event Gateway)
│   ├── pom.xml (service-specific Maven config)
│   ├── ./mvnw (Maven wrapper)
│   ├── CLAUDE.md (detailed service architecture)
│   ├── Dockerfile (Docker image definition)
│   ├── src/main/java/com/example/eventsService/
│   │   ├── controller/ (REST endpoints)
│   │   ├── service/ (business logic)
│   │   ├── repository/ (data access)
│   │   ├── client/ (Account Service HTTP client + circuit breaker)
│   │   ├── model/ (JPA entities, DTOs, enums)
│   │   ├── config/ (RestClient bean)
│   │   └── exception/ (error handling)
│   └── src/test/java/ (unit & integration tests)
│
└── accountService/ (Port 8081 — internal Account Processor)
    ├── pom.xml (service-specific Maven config)
    ├── ./mvnw (Maven wrapper)
    ├── CLAUDE.md (detailed service architecture)
    ├── Dockerfile (Docker image definition)
    ├── src/main/java/com/example/accountService/
    │   ├── controller/ (REST endpoints)
    │   ├── service/ (business logic)
    │   ├── repository/ (data access + custom queries)
    │   ├── model/ (JPA entities, DTOs, enums)
    │   └── exception/ (error handling)
    └── src/test/java/ (unit & integration tests)
```

---

## Common Development Tasks

### Adding a New Endpoint

1. **Decide:** Should it go in eventsService (public) or accountService (internal)?
2. **Create Controller method** in the appropriate `controller/` package
3. **Add Service method** for business logic (if needed)
4. **Add Repository query** if new data access is required
5. **Update the service-specific CLAUDE.md** with endpoint docs
6. **Write tests:** unit tests for service logic, integration tests for endpoint
7. **Test both services together:** `docker-compose up -d` to verify end-to-end behavior

### Adding a Custom Metric

1. Inject `MeterRegistry` from Micrometer (Spring auto-provides it)
2. Create counter/timer/gauge as needed
3. Increment/update in business logic
4. Query via `/actuator/prometheus`

Example (counter):
```java
@Autowired
private MeterRegistry meterRegistry;

meterRegistry.counter("my.metric", "status", "success").increment();
```

### Debugging Traces in Zipkin

1. Ensure `docker-compose up -d` is running
2. Navigate to http://localhost:9411
3. Search by service name (eventsService, accountService)
4. Click trace ID to see full request path, latencies, error details

### Testing Circuit Breaker Behavior

1. Start services locally: `cd eventsService && ./mvnw spring-boot:run` (Terminal 1) and `cd accountService && ./mvnw spring-boot:run` (Terminal 2)
2. Submit events via curl (see eventsService/CLAUDE.md for examples)
3. Stop accountService (Ctrl+C in Terminal 2) to simulate failure
4. Continue submitting events; after 50% failures in 10 calls, circuit opens (503 responses)
5. Restart accountService; after 30 seconds, circuit half-opens and tests 3 calls
6. Once successful, circuit closes and normal operation resumes

---

## Key Files to Know

| File | Purpose |
|------|---------|
| `AGENTS.md` | Guidance for AI agents (Claude Code) on service structure and conventions |
| `docker-compose.yml` | Full local environment: services + Zipkin + Prometheus + OTel Collector |
| `otel-collector-config.yaml` | OpenTelemetry Collector routing (traces → Zipkin) |
| `prometheus.yml` | Prometheus scrape config for eventsService and accountService |
| `eventsService/pom.xml` | Dependencies (restclient, resilience4j, opentelemetry, etc.) |
| `accountService/pom.xml` | Dependencies (data-jpa, opentelemetry, etc.) |

---

## Important Architectural Constraints

**Do NOT:**
- Create a root `pom.xml` aggregator — services are intentionally independent
- Share database instances between services (each uses its own H2 instance)
- Remove the `@CircuitBreaker` annotation from `AccountServiceClient.applyTransaction()` without adding explicit retry logic
- Store `balance` as a denormalized field in Account entity — must remain computed at query time
- Use `javax.*` namespaces — must use `jakarta.*` (Spring Boot 4 + Jakarta EE 11)

**DO:**
- Update both service-specific CLAUDE.md files when making architectural changes
- Test behavior changes against **both services simultaneously** (Docker Compose is easiest)
- Preserve idempotency guarantees: don't remove `findById` checks or UNIQUE constraints
- Use the existing exception handlers (`GlobalExceptionHandler`) for consistent error responses
- Include `trace.id` and `span.id` in logs for debugging (already set up via ECS format)

---

## Troubleshooting

**Q: "Cannot find class RestClient.Builder"**
A: Ensure `spring-boot-starter-restclient` is in pom.xml. Spring Boot 4.0.6 requires it explicitly (not included in webmvc).

**Q: H2 dialect warning in logs**
A: Harmless. Hibernate auto-detects H2. Can be removed by deleting `spring.jpa.database-platform` from application.properties.

**Q: Services can't reach each other locally**
A: Use `http://localhost:8081` (not `http://accountService:8081`) when running services outside Docker. Update `account.service.base-url` in eventsService/src/main/resources/application.properties.

**Q: No traces in Zipkin**
A: Verify docker-compose is running with `docker ps`. Check that `otel-collector` and `zipkin` containers are up. OTel endpoint must be reachable: `http://localhost:4318/v1/traces` for local, `http://otel-collector:4318/v1/traces` for Docker.

**Q: Circuit breaker staying open**
A: Circuit opens after 50% failure rate in a 10-call window. To manually reset, restart eventsService. To test recovery, fix the underlying issue (e.g., restart accountService) and wait 30 seconds for circuit to half-open.

**Q: Prometheus metrics are empty**
A: Metrics are cumulative counters. Submit a few events, then query `/actuator/prometheus`. Look for `events_submitted_total` or `transactions_applied_total` (Prometheus replaces `.` with `_` in metric names).

---

## Next Steps

- For detailed eventsService development: see [eventsService/CLAUDE.md](eventsService/CLAUDE.md)
- For detailed accountService development: see [accountService/CLAUDE.md](accountService/CLAUDE.md)
- To set up additional services: follow the same structure (service-specific pom.xml, Maven wrapper, CLAUDE.md documentation)
- For production deployment: replace H2 with persistent databases, add message queues (Kafka), and configure multi-instance deployments with distributed locks

---

**Last Updated:** 2026-06-04
**Java:** 21 | **Spring Boot:** 4.0.6 | **Jakarta EE:** 11
