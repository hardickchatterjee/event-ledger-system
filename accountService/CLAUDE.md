# Account Service — Internal Transaction Processor

## Overview

The **Account Service** is an internal service (not exposed to external clients) that manages account state and processes transactions. It is called only by the Event Gateway API on port 8081. Its role is to:

- Accept transactions (credits and debits) from the Gateway
- Auto-create accounts on first transaction
- Compute account balances
- Maintain transaction history
- Deduplicate transactions by `eventId`

## Architecture

- **Port:** 8081 (HTTP, internal only)
- **Database:** H2 in-memory (`accountsdb`)
- **Framework:** Spring Boot 4.0.6, Spring MVC, Spring Data JPA
- **Language:** Java 21

## Key Design Patterns

### 1. Deduplication by eventId

Every `Transaction` has a UNIQUE constraint on `eventId`. Combined with an application-level `existsByEventId` check:

- **First submission:** Check fails, transaction is created
- **Duplicate submission:** Check succeeds, return existing transaction (idempotent)
- **DB race condition:** UNIQUE constraint at database level catches duplicates and raises `DataIntegrityViolationException`, which is caught and handled gracefully

See: `AccountService.applyTransaction()` — the check happens before creation.

### 2. Auto-Create Account

When a transaction arrives for an unknown account:

```java
accountRepository.findById(accountId).orElseGet(() -> {
    Account newAccount = Account.builder().accountId(accountId).build();
    return accountRepository.save(newAccount);
});
```

No explicit account creation endpoint exists; accounts are created on-demand by the first transaction.

### 3. Balance Computation at Query Time

There is no `balance` column on the `Account` entity. Instead:

```java
@Query("SELECT COALESCE(SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END), 0) " +
       "FROM Transaction t WHERE t.accountId = :accountId")
BigDecimal computeBalance(@Param("accountId") String accountId);
```

This query **sums all transactions** for the account. The balance is always correct regardless of insertion order — transactions arriving out of order don't affect the result because we're computing a sum, not a running total.

## File Structure

```
src/main/java/com/example/accountService/
├── AccountServiceApplication.java    @SpringBootApplication entry point
├── model/
│   ├── TransactionType.java          Enum: CREDIT, DEBIT (duplicated from eventsService)
│   ├── Account.java                  @Entity, PK = accountId
│   ├── Transaction.java              @Entity, UUID PK, UNIQUE eventId
│   ├── TransactionRequest.java       DTO for incoming requests
│   ├── TransactionResponse.java      DTO for responses
│   ├── BalanceResponse.java          DTO for balance queries
│   └── AccountDetailsResponse.java   DTO for account details
├── repository/
│   ├── AccountRepository.java        JpaRepository<Account, String>
│   └── TransactionRepository.java    JpaRepository<Transaction, UUID>
├── service/
│   └── AccountService.java           Business logic: deduplication, balance, auto-create
├── controller/
│   └── AccountController.java        3 REST endpoints (no public listing)
└── exception/
    └── GlobalExceptionHandler.java   @RestControllerAdvice for error responses
```

## Endpoints

All endpoints are **internal only** — not exposed to external clients. The Event Gateway calls them on behalf of the client.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/accounts/{accountId}/transactions` | Apply a transaction (deduped by eventId) |
| GET | `/accounts/{accountId}/balance` | Get current balance and transaction count |
| GET | `/accounts/{accountId}` | Get account details + recent 20 transactions |
| GET | `/health-check` | Custom service health (database connectivity) |
| GET | `/actuator/health` | Spring Actuator health with details |
| GET | `/actuator/prometheus` | Prometheus metrics endpoint |

## Core Entities

### Account

| Field | Type | Notes |
|-------|------|-------|
| `accountId` | String | `@Id` — natural PK |
| `createdAt` | Instant | Set in `@PrePersist` |

No `balance` field — computed at query time.

### Transaction

| Field | Type | Notes |
|-------|------|-------|
| `transactionId` | UUID | `@Id @GeneratedValue(UUID)` — surrogate PK |
| `eventId` | String | UNIQUE constraint — links to event from Gateway |
| `accountId` | String | Foreign key to Account (implicit) |
| `type` | TransactionType | CREDIT or DEBIT |
| `amount` | BigDecimal | Positive value; sign determined by type |
| `currency` | String | ISO 4217 code |
| `appliedAt` | Instant | Set in `@PrePersist` |

## Key Classes

### AccountService

**Role:** Business logic for account queries and transaction processing.

**applyTransaction(accountId, request) → TransactionResponse:**
1. Check `transactionRepository.existsByEventId(eventId)` — if true, return existing transaction
2. Auto-create account if it doesn't exist
3. Build and save `Transaction`
4. Catch `DataIntegrityViolationException` (DB-level duplicate) → return existing transaction
5. Return `TransactionResponse`

**getBalance(accountId) → BalanceResponse:**
- Throws 404 if account doesn't exist
- Calls `computeBalance()` JPQL query
- Returns balance + transaction count

**getAccountDetails(accountId) → AccountDetailsResponse:**
- Throws 404 if account doesn't exist
- Computes balance via `computeBalance()`
- Returns account + recent 20 transactions

### TransactionRepository

**Custom Query Methods:**

```java
boolean existsByEventId(String eventId);
Optional<Transaction> findByEventId(String eventId);
List<Transaction> findByAccountIdOrderByAppliedAtDesc(String accountId);
long countByAccountId(String accountId);

@Query("SELECT COALESCE(SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END), 0) " +
       "FROM Transaction t WHERE t.accountId = :accountId")
BigDecimal computeBalance(@Param("accountId") String accountId);
```

### AccountController

**Endpoints:**
- **POST** `/accounts/{accountId}/transactions` → 200 OK + `TransactionResponse`
- **GET** `/accounts/{accountId}/balance` → 200 OK + `BalanceResponse`, or 404
- **GET** `/accounts/{accountId}` → 200 OK + `AccountDetailsResponse`, or 404

No listing of accounts (no `GET /accounts`).

## Database Schema

Created by Hibernate (ddl-auto=create-drop):

```sql
CREATE TABLE accounts (
    account_id VARCHAR(255) PRIMARY KEY NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE transactions (
    transaction_id UUID PRIMARY KEY NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    event_id VARCHAR(255) NOT NULL UNIQUE,
    type ENUM('CREDIT','DEBIT') NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL
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
- No `spring-boot-starter-restclient` — Account Service makes no outbound calls
- `spring-boot-starter-opentelemetry` provides OTel SDK and auto-config
- Metrics are exported to Prometheus and traces to OTel Collector

## Jakarta EE Namespaces

All imports use `jakarta.*`:
- `jakarta.persistence.*` — JPA annotations
- `jakarta.validation.constraints.*` — Bean Validation
- `jakarta.servlet.*` — Servlet API
- `jakarta.transaction.*` — Transaction annotations

## Configuration (application.properties)

```properties
spring.application.name=accountService
server.port=8081

spring.datasource.url=jdbc:h2:mem:accountsdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true

spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

# Observability
logging.structured.format.console=ecs
management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.health.show-details=always
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces
management.tracing.sampling.probability=1.0
```

**Key configurations:**
- H2 console accessible at `/h2-console` for debugging
- Separate database instance (`accountsdb`) from Event Gateway
- Structured JSON logging (ECS) with trace context
- OTel tracing endpoint (overridable via `MANAGEMENT_OTLP_TRACING_ENDPOINT` env var)
- Prometheus metrics endpoint: `/actuator/prometheus`
- Custom metrics: `transactions.applied_total{status=...}` counter

## Example Workflows

### Scenario 1: First Transaction for New Account

```bash
POST /accounts/new-acct/transactions
{
  "eventId": "evt-abc",
  "type": "CREDIT",
  "amount": 100.00,
  "currency": "USD"
}
```

1. `existsByEventId("evt-abc")` → false
2. `accountRepository.findById("new-acct")` → empty
3. Auto-create account "new-acct"
4. Create transaction, save it
5. Return 200 OK + `TransactionResponse`

### Scenario 2: Duplicate Transaction (Idempotent Replay)

```bash
POST /accounts/new-acct/transactions
{
  "eventId": "evt-abc",
  "type": "CREDIT",
  "amount": 100.00,
  "currency": "USD"
}
```

1. `existsByEventId("evt-abc")` → true
2. `findByEventId("evt-abc")` → return existing `Transaction`
3. Return 200 OK + same `TransactionResponse` (same UUIDs, timestamps)

Balance is still 100.00 — not double-counted.

### Scenario 3: Get Balance After Multiple Transactions

```bash
# Previous: CREDIT 100, DEBIT 30
GET /accounts/new-acct/balance
```

1. `computeBalance("new-acct")` runs:
   ```
   SUM(CASE WHEN CREDIT THEN 100, WHEN DEBIT THEN -30) = 70
   ```
2. `countByAccountId("new-acct")` → 2
3. Return 200 OK:
   ```json
   {
     "accountId": "new-acct",
     "balance": 70.0000,
     "currency": "USD",
     "transactionCount": 2
   }
   ```

## Observability Features

### Structured JSON Logging
- All logs output in ECS format with `@timestamp`, `service.name`, `log.level`, `trace.id`, `span.id`
- Enabled via `logging.structured.format.console=ecs`
- Traces propagated from eventsService via W3C `traceparent` headers

### Distributed Tracing
- Receives traces from eventsService (parent spans via headers)
- Auto-instruments database queries, service methods
- Exports to OTel Collector (OTLP HTTP) → Zipkin
- Full request path visible: Gateway → Account Service, with timing and errors

### Metrics
- Prometheus endpoint: `GET /actuator/prometheus`
- Custom counter: `transactions.applied_total{status=...}` — ACCEPTED, DUPLICATE
- Auto-instrumented: JVM, HTTP, database

## Deployment Notes

### Scalability

This is a reference implementation using H2 in-memory databases. For production:

- **Replace H2** with PostgreSQL, MySQL, or other persistent database
- **Shared database:** eventsService and accountService could share a single database with separate schemas
- **Caching:** Add Redis for balance caching (compute balance on write, cache on read)
- **Async:** Use message queues (Kafka) for async event processing instead of synchronous REST
- **Monitoring:** Observability stack (OTel Collector, Zipkin, Prometheus) is already integrated

### Deduplication Guarantees

The current implementation is safe for single-instance deployments. For multi-instance:

- The UNIQUE constraint on `eventId` provides a database-level guard
- For true distributed deduplication, consider:
  - Distributed locks (Redis, Zookeeper)
  - Event sourcing with version vectors
  - Outbox pattern (store + send in single transaction)

## Docker Deployment

Both services run in Docker with full observability:

```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system
docker-compose up -d
```

This starts:
- **eventsService** (port 8080) with circuit breaker & tracing
- **accountService** (port 8081) with distributed tracing
- **Zipkin** (port 9411) — trace visualization
- **OTel Collector** (ports 4317/4318) — trace aggregation
- **Prometheus** (port 9090) — metrics visualization

---

**Last Updated:** 2026-06-04
**Observability Added:** 2026-06-04 — OpenTelemetry, Prometheus, structured logging, metrics
