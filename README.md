# Event Ledger System

A distributed, event-driven transaction processing system built with **Java 21** and **Spring Boot 4.0.6**. Processes financial transaction events with guaranteed idempotency, out-of-order tolerance, resilience patterns, and comprehensive observability through OpenTelemetry and Prometheus.

## 📋 Overview

The Event Ledger System consists of two independent Spring Boot microservices:

- **Event Gateway (eventsService)** - Port 8080
  - Public-facing REST API for submitting financial transactions
  - Ensures idempotency via natural key (`eventId`)
  - Handles out-of-order event arrivals via business-time ordering
  - Implements circuit breaker pattern for resilience
  - Rate limiting for API protection

- **Account Service (accountService)** - Port 8081
  - Internal service managing account state and balances
  - Auto-creates accounts on first transaction
  - Computes balances at query time (no stored balance field)
  - Deduplicates transactions by `eventId`

## 🏗️ Architecture

### Key Design Patterns

#### 1. **Idempotency via Natural Primary Key**
- `eventId` (client-provided) is the primary key on both `EventRecord` and `Transaction`
- Duplicate submissions return the same response without reprocessing
- Prevents double-charging on network retries

#### 2. **Out-of-Order Event Handling**
- Events stored with `eventTimestamp` (business time, not ingestion time)
- Queries return events ordered by `eventTimestamp` ASC
- Balance computed as sum of all transactions → order-independent
- System safe for events arriving out of chronological order

#### 3. **Circuit Breaker Pattern (Resilience4j)**
- Event Gateway → Account Service communication wrapped with circuit breaker
- **Configuration:**
  - Window size: 10 calls
  - Failure threshold: 50%
  - Open state duration: 30 seconds
  - Half-open test calls: 3
- **Behavior:**
  - Circuit CLOSED: normal operation
  - Circuit OPEN: returns 503 Service Unavailable
  - Circuit HALF_OPEN: tests recovery with 3 calls
- **Data Safety:** Events NOT saved until Account Service confirms

#### 4. **Distributed Tracing & Observability**
- **OpenTelemetry**: W3C `traceparent` headers propagate trace context
- **Zipkin**: Visualizes full request path across services
- **Prometheus**: Collects metrics (events, transactions, JVM stats)
- **Structured Logging**: ECS JSON format with `trace.id`, `span.id`, `service.name`

### Database Architecture
- H2 in-memory databases (separate instances per service)
- `ddl-auto=create-drop` for development (recreate schema on startup)
- Suitable for reference implementation; replace with PostgreSQL for production

## 🚀 Getting Started

### Prerequisites
- Java 21 (or higher)
- Maven 3.9+ (or use bundled `./mvnw`)
- Docker & Docker Compose (optional, for observability stack)

### Option 1: Docker Compose (Recommended)

**Start entire stack with observability:**

```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system
docker-compose up -d
```

This starts:
- **eventsService** - http://localhost:8080 (Event Gateway)
- **accountService** - http://localhost:8081 (Account Service)
- **Zipkin** - http://localhost:9411 (Trace visualization)
- **OTel Collector** - ports 4317/4318 (Trace ingestion)
- **Prometheus** - http://localhost:9090 (Metrics dashboard)

**Verify services are running:**

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
```

**View logs:**

```bash
docker-compose logs -f eventsService
docker-compose logs -f accountService
```

**Stop services:**

```bash
docker-compose down
```

### Option 2: Local Development (Two Terminals)

**Terminal 1 — Event Gateway:**

```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system/eventsService
./mvnw clean spring-boot:run
```

**Terminal 2 — Account Service:**

```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system/accountService
./mvnw clean spring-boot:run
```

Services available at:
- eventsService: http://localhost:8080
- accountService: http://localhost:8081

## 🧪 Running Tests

### Run All Tests

**eventsService:**
```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system/eventsService
./mvnw clean test
```

**accountService:**
```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system/accountService
./mvnw clean test
```

### Test Coverage

#### eventsService Tests (16 tests)
- **Unit Tests (8):**
  - Event submission (new, duplicate, error handling)
  - Idempotency guarantee
  - Out-of-order event handling
  - Event retrieval by ID and account
  
- **Integration Tests (7):**
  - Full event lifecycle via EventService
  - Account ordering verification
  - Empty account handling
  - Out-of-order submission correctness

#### accountService Tests (19 tests)
- **Unit Tests (9):**
  - Transaction application (new, duplicate)
  - Auto-account creation
  - Database-level duplicate handling
  - Balance computation
  - Account details retrieval
  
- **Integration Tests (9):**
  - Full transaction lifecycle via AccountService
  - Idempotency verification
  - Balance computation across multiple transactions
  - Recent transaction limiting (max 20)
  - Balance independence from transaction order

### Test Results

```bash
# Both services should show:
# "BUILD SUCCESS"
# "Tests run: X, Failures: 0, Errors: 0, Skipped: 0"
```

## 📡 API Examples

### Submit an Event (Event Gateway)

```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 100.50,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z",
    "metadata": {"source": "web", "region": "US"}
  }'
```

**Response (200 OK):**
```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 100.50,
  "currency": "USD",
  "status": "PROCESSED",
  "eventTimestamp": "2026-05-15T09:00:00Z",
  "ingestedAt": "2026-05-15T10:30:00Z"
}
```

### Get Event by ID

```bash
curl http://localhost:8080/events/evt-001
```

### List Events for Account (ordered by timestamp)

```bash
curl "http://localhost:8080/events?account=acct-123"
```

### Apply Transaction (Account Service - Internal)

```bash
curl -X POST http://localhost:8081/accounts/acct-123/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "type": "CREDIT",
    "amount": 100.50,
    "currency": "USD"
  }'
```

### Get Account Balance

```bash
curl http://localhost:8081/accounts/acct-123/balance
```

**Response:**
```json
{
  "accountId": "acct-123",
  "balance": 150.50,
  "currency": "USD",
  "transactionCount": 2
}
```

### Get Account Details with Recent Transactions

```bash
curl http://localhost:8081/accounts/acct-123
```

## 🔄 Resiliency & Error Handling

### Circuit Breaker Behavior

**Scenario 1: Account Service is healthy**
```
Request → Event Gateway → [Circuit CLOSED] → Account Service → 200 OK
```

**Scenario 2: Account Service returns 5xx errors**
```
Requests 1-5: Gate passes → Account Service fails
Request 6-10: 50% failure rate → Circuit opens
Request 11+: [Circuit OPEN] → 503 Service Unavailable (no Account Service call)
```

**Scenario 3: Circuit recovery**
```
[Circuit OPEN for 30s] → [Circuit HALF_OPEN] → 3 test calls → Success → [Circuit CLOSED]
```

### Error Responses

| Status | Scenario | Behavior |
|--------|----------|----------|
| 200 OK | Event processed successfully | Event stored, Account Service accepted |
| 400 Bad Request | Validation fails | Invalid amount, missing fields, invalid currency |
| 503 Service Unavailable | Account Service unreachable | Circuit open or Account Service down |
| 404 Not Found | Event/Account not found | No matching record |

### Retry Strategy

- **Client responsibility**: Implement exponential backoff
- **Idempotency guarantee**: Safe to retry with same `eventId`
- **Circuit breaker feedback**: 503 response indicates wait (30s) before retrying
- **Rate limiting**: 100 requests/minute (adjust via `resilience4j.ratelimiter` config)

## 📊 Observability

### 1. Distributed Tracing (Zipkin)

**View traces:**
```bash
# Navigate to http://localhost:9411
# Search by service name: eventsService, accountService
# Click trace ID to see full request path with timings
```

**What's traced:**
- HTTP requests (client & server)
- Database queries
- Thread context across async operations
- Latency breakdown by service

### 2. Structured Logging (ECS Format)

**All logs include:**
```json
{
  "@timestamp": "2026-05-15T10:30:00Z",
  "service": {"name": "eventsService"},
  "log": {"level": "INFO"},
  "trace": {"id": "abc123def456"},
  "span": {"id": "xyz789"},
  "message": "Event submitted successfully"
}
```

**Enable/disable:**
```properties
# application.properties
logging.structured.format.console=ecs  # Enable ECS format
```

### 3. Prometheus Metrics

**Custom counters:**
- `events_submitted_total{status=PROCESSED|PENDING|FAILED}` - Event submissions
- `transactions_applied_total{status=ACCEPTED|DUPLICATE}` - Transaction applications
- `rate_limiter_calls_total` - Rate limiter invocations

**Auto-instrumented:**
- JVM memory, threads, GC
- HTTP server/client latencies
- Database connection pool

**Query metrics:**
```bash
curl http://localhost:9090/api/v1/query?query=events_submitted_total
```

## 🏗️ Project Structure

```
event-ledger-system/
├── README.md                          (this file)
├── CLAUDE.md                          (high-level architecture)
├── docker-compose.yml                 (local dev environment)
├── otel-collector-config.yaml         (trace collection)
├── prometheus.yml                     (metrics scrape config)
│
├── eventsService/                     (Port 8080 — Public Gateway)
│   ├── pom.xml
│   ├── CLAUDE.md                      (service-specific docs)
│   ├── src/main/java/com/example/eventsService/
│   │   ├── controller/                REST endpoints
│   │   ├── service/                   Business logic
│   │   ├── repository/                Data access
│   │   ├── client/                    Account Service client + circuit breaker
│   │   ├── model/                     Entities, DTOs, enums
│   │   ├── config/                    RestClient bean
│   │   └── exception/                 Global error handler
│   └── src/test/java/                 Unit & integration tests
│
└── accountService/                    (Port 8081 — Internal Processor)
    ├── pom.xml
    ├── CLAUDE.md                      (service-specific docs)
    ├── src/main/java/com/example/accountService/
    │   ├── controller/                REST endpoints
    │   ├── service/                   Business logic
    │   ├── repository/                Data access + custom queries
    │   ├── model/                     Entities, DTOs, enums
    │   └── exception/                 Global error handler
    └── src/test/java/                 Unit & integration tests
```

## 🔧 Configuration

### eventsService (application.properties)

```properties
# Server
spring.application.name=eventsService
server.port=8080

# Database (H2 in-memory)
spring.datasource.url=jdbc:h2:mem:eventsdb
spring.jpa.hibernate.ddl-auto=create-drop

# Observability
logging.structured.format.console=ecs
management.endpoints.web.exposure.include=health,info,prometheus
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces
management.tracing.sampling.probability=1.0

# RestClient timeout
spring.http.client.connect-timeout=5s
spring.http.client.read-timeout=5s

# Circuit breaker
resilience4j.circuitbreaker.instances.accountService.sliding-window-size=10
resilience4j.circuitbreaker.instances.accountService.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.accountService.wait-duration-in-open-state=30s

# Rate limiter
resilience4j.ratelimiter.instances.eventSubmission.register-health-indicator=true
resilience4j.ratelimiter.instances.eventSubmission.limit-refresh-period=1m
resilience4j.ratelimiter.instances.eventSubmission.limit-for-period=100
```

### accountService (application.properties)

```properties
# Server
spring.application.name=accountService
server.port=8081

# Database (H2 in-memory)
spring.datasource.url=jdbc:h2:mem:accountsdb
spring.jpa.hibernate.ddl-auto=create-drop

# Observability
logging.structured.format.console=ecs
management.endpoints.web.exposure.include=health,info,prometheus
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces
management.tracing.sampling.probability=1.0
```

## 🚢 Production Deployment

### Changes for Production

1. **Database**
   - Replace H2 with PostgreSQL, MySQL, or cloud-managed database
   - Use connection pooling (HikariCP configured by default)
   - Run schema migrations (Flyway/Liquibase)

2. **Message Queue** (async processing)
   - Replace synchronous REST with Apache Kafka or RabbitMQ
   - Implement event sourcing or outbox pattern for guaranteed delivery

3. **Distributed Locks** (multi-instance deduplication)
   - Use Redis, PostgreSQL, or Zookeeper for distributed locks
   - Protect critical sections (e.g., duplicate check + insert)

4. **Configuration Management**
   - Move secrets to HashiCorp Vault, AWS Secrets Manager, or similar
   - Externalize config via Spring Cloud Config or environment variables

5. **Monitoring & Alerts**
   - Configure Prometheus scrape intervals and retention
   - Set up Grafana dashboards and alert rules
   - Integrate PagerDuty or OpsGenie for on-call alerts

6. **Load Balancing**
   - Deploy behind AWS ALB, nginx, or cloud load balancer
   - Enable health check endpoints (`/actuator/health`)
   - Configure auto-scaling (e.g., AWS ASG)

### Example Production Docker Image

```dockerfile
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY target/eventsService-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
```

## 🐛 Troubleshooting

### Q: Services can't reach each other locally
**A:** Update `account.service.base-url` in eventsService properties:
```properties
account.service.base-url=http://localhost:8081  # local
# or
account.service.base-url=http://accountService:8081  # Docker
```

### Q: Circuit breaker staying open
**A:** Circuit opens after 50% failure in 10-call window. To fix:
1. Verify Account Service is healthy: `curl http://localhost:8081/actuator/health`
2. Wait 30 seconds for circuit to half-open
3. Send successful requests to trigger circuit close

### Q: No traces in Zipkin
**A:** Verify OTel Collector is running:
```bash
docker ps | grep otel
# If not running: docker-compose up -d otel-collector
```

### Q: Prometheus metrics are empty
**A:** Metrics are cumulative. Submit a few events, then:
```bash
curl http://localhost:9090/api/v1/query?query=events_submitted_total
```

### Q: H2 console access
**A:** View H2 console at `http://localhost:8080/h2-console`:
- **JDBC URL**: `jdbc:h2:mem:eventsdb`
- **User**: `sa`
- **Password**: (leave blank)

## 📚 Additional Resources

- **High-level architecture**: See [CLAUDE.md](CLAUDE.md)
- **eventsService details**: See [eventsService/CLAUDE.md](eventsService/CLAUDE.md)
- **accountService details**: See [accountService/CLAUDE.md](accountService/CLAUDE.md)
- **Resilience4j docs**: https://resilience4j.readme.io
- **OpenTelemetry**: https://opentelemetry.io
- **Spring Boot**: https://spring.io/projects/spring-boot

## 📝 License

This is a reference implementation for educational purposes.

---

**Last Updated:** 2026-06-04  
**Java:** 21 | **Spring Boot:** 4.0.6 | **Jakarta EE:** 11
