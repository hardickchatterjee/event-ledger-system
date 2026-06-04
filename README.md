# Event Ledger System

A distributed, event-driven transaction processing system built with **Java 21** and **Spring Boot 4.0.6**. Processes financial transaction events with guaranteed idempotency, out-of-order tolerance, resilience patterns, and comprehensive observability.

## 🎯 Project Status

- ✅ **Implementation**: 100% complete (27 Java files)
- ⚠️ **Test Coverage**: Needs comprehensive automated tests
- ✅ **Docker Ready**: Full stack with Zipkin, Prometheus, OpenTelemetry Collector
- ✅ **Production Features**: Circuit breaker, graceful degradation, distributed tracing, structured logging

## 📋 Overview

The Event Ledger System consists of two independent microservices:

```
┌────────────────────────────────┐
│    Client / External System    │
└────────────────┬───────────────┘
                 │ HTTP (REST)
                 ▼
┌────────────────────────────────────────────┐
│  Event Gateway API (eventsService)         │
│  Port: 8080                                │
│  • Receives transaction events             │
│  • Idempotency check (eventId)             │
│  • Stores event records                    │
│  • Calls Account Service (with circuit     │
│    breaker protection)                     │
└────────────────┬──────────────────────────┘
                 │
                 ▼ (W3C traceparent)
┌────────────────────────────────────────────┐
│  Account Service (accountService)          │
│  Port: 8081                                │
│  • Manages account state & balances        │
│  • Deduplicates transactions               │
│  • Auto-creates accounts                   │
│  • Computes balance at query time          │
└────────────────────────────────────────────┘
      │         │         │
      ▼         ▼         ▼
┌────────────────────────────────────────────┐
│  Observability Stack                       │
│  • Zipkin (trace visualization)            │
│  • OTel Collector (trace aggregation)      │
│  • Prometheus (metrics)                    │
│  • Structured JSON logs (ECS format)       │
└────────────────────────────────────────────┘
```

## 🚀 Quick Start

### Prerequisites

- **Java 21+** (`java -version`)
- **Docker & Docker Compose** (for full stack)
- **Maven** (included via `./mvnw`)

### Option 1: Docker Compose (Recommended)

Start the entire stack with one command:

```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system
docker-compose up -d
```

Verify all services are running:
```bash
docker-compose ps
```

This starts:
- **eventsService** (port 8080) — Event Gateway
- **accountService** (port 8081) — Account processor
- **Zipkin** (port 9411) — Trace visualization
- **OTel Collector** (ports 4317/4318) — Trace collection
- **Prometheus** (port 9090) — Metrics dashboard

### Option 2: Local Development (Two Terminals)

**Terminal 1 — Event Gateway:**
```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system/eventsService
./mvnw spring-boot:run
```

**Terminal 2 — Account Service:**
```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system/accountService
./mvnw spring-boot:run
```

Services will be available at:
- eventsService: http://localhost:8080
- accountService: http://localhost:8081

## 🏗️ Architecture

### Core Design Patterns

#### 1. Idempotency
- `eventId` (client-provided) is the natural primary key on `EventRecord`
- Duplicate submissions return the existing event without re-processing
- Prevents double-charging on network retries
- Implementation: `eventsService/EventService.submitEvent()` and `accountService/TransactionRepository` UNIQUE constraint

#### 2. Out-of-Order Event Tolerance
- Events stored with `eventTimestamp` (business time, not ingestion order)
- Queries order results by `eventTimestamp ASC`
- Balance computed as sum of all transactions (insertion order irrelevant)
- System safe for events arriving out of chronological order

#### 3. Circuit Breaker Resilience
- **Pattern**: Resilience4j on eventsService → accountService calls
- **Threshold**: 50% failure rate in 10-call sliding window
- **Open State**: 30-second cooldown, then 3 test calls in half-open
- **Behavior**: Network failures trigger 503 Service Unavailable (not 500)
- **Data Safety**: Event NOT saved until Account Service confirms

#### 4. Distributed Tracing
- OpenTelemetry auto-instruments all requests
- W3C `traceparent` headers propagate trace context across services
- Traces exported to OTel Collector → Zipkin (100% sample rate)
- Both services log trace IDs in structured JSON format

## 📖 API Reference

### Event Gateway API (eventsService) — Port 8080

#### POST /events
Submit a transaction event.

**Request:**
```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": {
    "source": "mainframe-batch",
    "batchId": "B-9042"
  }
}
```

**Response (200 OK):**
```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "status": "PROCESSED",
  "ingestedAt": "2026-06-04T14:30:00Z"
}
```

**Response (503 Service Unavailable):**
```json
{
  "error": "Service Unavailable",
  "message": "Account Service is currently unavailable. Please try again later."
}
```

#### GET /events/{id}
Retrieve a single event by ID.

```bash
curl http://localhost:8080/events/evt-001
```

**Response (200 OK):**
```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "status": "PROCESSED",
  "ingestedAt": "2026-06-04T14:30:00Z"
}
```

#### GET /events?account={accountId}
List events for an account, ordered by event timestamp.

```bash
curl "http://localhost:8080/events?account=acct-123"
```

**Response (200 OK):**
```json
[
  {
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z",
    "status": "PROCESSED"
  },
  {
    "eventId": "evt-002",
    "accountId": "acct-123",
    "type": "DEBIT",
    "amount": 50.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T10:00:00Z",
    "status": "PROCESSED"
  }
]
```

#### GET /health-check
Custom health check endpoint.

```bash
curl http://localhost:8080/health-check
```

### Account Service API (accountService) — Port 8081

#### GET /accounts/{accountId}/balance
Get the current balance for an account.

```bash
curl http://localhost:8081/accounts/acct-123/balance
```

**Response (200 OK):**
```json
{
  "accountId": "acct-123",
  "balance": 100.0000,
  "currency": "USD",
  "transactionCount": 2
}
```

#### GET /accounts/{accountId}
Get account details and recent transactions.

```bash
curl http://localhost:8081/accounts/acct-123
```

#### POST /accounts/{accountId}/transactions
Apply a transaction to an account (internal use only).

#### GET /health-check
Custom health check endpoint.

## 🧪 Testing

### Test Scenarios

Run these scenarios to verify all functionality:

### 1. Idempotency Test

**Objective**: Verify duplicate submissions are handled correctly without double-charging.

**Step 1: Submit an event**
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 100.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'
```

**Expected**: 200 OK with event details

**Step 2: Resubmit the same event**
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 100.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'
```

**Expected**: Same response (idempotent)

**Step 3: Check balance**
```bash
curl http://localhost:8081/accounts/acct-123/balance
```

**Expected**: 
```json
{
  "balance": 100.0000,
  "transactionCount": 1
}
```

✅ **Success**: Balance is 100 (not 200) — no double-charge

---

### 2. Out-of-Order Event Handling

**Objective**: Verify events can arrive out of order and are returned in correct chronological order.

**Step 1: Submit event with later timestamp**
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-002",
    "accountId": "acct-456",
    "type": "CREDIT",
    "amount": 200.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T10:00:00Z"
  }'
```

**Step 2: Submit event with earlier timestamp (arriving out of order)**
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-003",
    "accountId": "acct-456",
    "type": "DEBIT",
    "amount": 50.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'
```

**Step 3: Query events for the account**
```bash
curl "http://localhost:8080/events?account=acct-456"
```

**Expected**: Events ordered by eventTimestamp ASC (evt-003 before evt-002)

**Step 4: Verify balance**
```bash
curl http://localhost:8081/accounts/acct-456/balance
```

**Expected**: 
```json
{
  "balance": 150.0000
}
```

✅ **Success**: Events ordered by timestamp, balance correct (200 - 50 = 150)

---

### 3. Balance Computation

**Objective**: Verify balance is correctly computed as sum of CREDITs − sum of DEBITs.

**Submit transactions:**
```bash
# CREDIT 1000
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-100",
    "accountId": "acct-789",
    "type": "CREDIT",
    "amount": 1000.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'

# DEBIT 300
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-101",
    "accountId": "acct-789",
    "type": "DEBIT",
    "amount": 300.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T10:00:00Z"
  }'

# CREDIT 500
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-102",
    "accountId": "acct-789",
    "type": "CREDIT",
    "amount": 500.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T11:00:00Z"
  }'

# DEBIT 100
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-103",
    "accountId": "acct-789",
    "type": "DEBIT",
    "amount": 100.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T12:00:00Z"
  }'
```

**Query balance:**
```bash
curl http://localhost:8081/accounts/acct-789/balance
```

**Expected**:
```json
{
  "balance": 1100.0000,
  "transactionCount": 4
}
```

✅ **Success**: (1000 + 500) − (300 + 100) = 1100

---

### 4. Input Validation

**Objective**: Verify invalid inputs are rejected with appropriate HTTP status codes.

**Missing required field:**
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-bad-1",
    "accountId": "acct-bad",
    "type": "CREDIT",
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'
```

**Expected**: 400 Bad Request

**Negative amount:**
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-bad-2",
    "accountId": "acct-bad",
    "type": "CREDIT",
    "amount": -50.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'
```

**Expected**: 400 Bad Request

**Invalid event type:**
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-bad-3",
    "accountId": "acct-bad",
    "type": "TRANSFER",
    "amount": 50.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'
```

**Expected**: 400 Bad Request

✅ **Success**: All invalid inputs rejected with 400

---

### 5. Graceful Degradation (Account Service Down)

**Objective**: Verify Gateway returns 503 when Account Service is unavailable.

**Step 1: Stop Account Service**
```bash
# If using Docker:
docker-compose stop accountService

# If running locally in Terminal 2, press Ctrl+C
```

**Step 2: Submit an event**
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-downtime-1",
    "accountId": "acct-down",
    "type": "CREDIT",
    "amount": 100.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'
```

**Expected**: 503 Service Unavailable

**Step 3: Verify event was NOT saved**
```bash
curl http://localhost:8080/events/evt-downtime-1
```

**Expected**: 404 Not Found

**Step 4: Restart Account Service**
```bash
docker-compose start accountService
```

**Step 5: Resubmit event**
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-downtime-1",
    "accountId": "acct-down",
    "type": "CREDIT",
    "amount": 100.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'
```

**Expected**: 200 OK

✅ **Success**: Returns 503 (not 500), event not saved, recovers when service restarts

---

### 6. Circuit Breaker Behavior

**Objective**: Verify circuit breaker opens after repeated failures.

**Step 1: Stop Account Service**
```bash
docker-compose stop accountService
```

**Step 2: Submit events until circuit opens (50% failure in 10-call window)**
```bash
for i in {1..5}; do
  curl -X POST http://localhost:8080/events \
    -H "Content-Type: application/json" \
    -d "{
      \"eventId\": \"evt-circuit-$i\",
      \"accountId\": \"acct-circuit\",
      \"type\": \"CREDIT\",
      \"amount\": 100.00,
      \"currency\": \"USD\",
      \"eventTimestamp\": \"2026-05-15T09:00:00Z\"
    }"
  sleep 0.5
done
```

**Expected**: All return 503

**Step 3: Submit one more event (circuit should be OPEN)**
```bash
time curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-circuit-fast",
    "accountId": "acct-circuit",
    "type": "CREDIT",
    "amount": 100.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'
```

**Expected**: Very fast response (< 100ms), indicating circuit is OPEN

**Step 4: Restart Account Service and wait for recovery**
```bash
docker-compose start accountService
sleep 35  # Wait for circuit to transition to HALF_OPEN
```

**Step 5: Submit event (circuit should recover)**
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-circuit-recover",
    "accountId": "acct-circuit-final",
    "type": "CREDIT",
    "amount": 100.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'
```

**Expected**: 200 OK (circuit recovered)

✅ **Success**: Circuit opens, fails fast, recovers after service restarts

---

### 7. Distributed Tracing

**Objective**: Verify trace IDs propagate from Gateway to Account Service.

**Step 1: Submit event and capture trace ID**
```bash
curl -X POST http://localhost:8080/events \
  -v \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-trace-1",
    "accountId": "acct-trace",
    "type": "CREDIT",
    "amount": 100.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }' 2>&1 | grep -i traceparent
```

**Expected**: `traceparent: 00-<trace-id>-<span-id>-01` header

**Step 2: Check logs for trace ID**
```bash
docker-compose logs eventsService | grep "trace.id"
docker-compose logs accountService | grep "trace.id"
```

**Expected**: Same trace ID in both services

**Step 3: View full trace in Zipkin**
```bash
# Open browser to:
open http://localhost:9411
```

Search by service name and inspect the full request path.

✅ **Success**: Trace ID propagates correctly across services

## 📊 Observability

### Zipkin — Trace Visualization

If using Docker Compose:
```bash
open http://localhost:9411
```

**Features:**
- Search by service name, trace ID, or time range
- View full request path from Gateway → Account Service
- Inspect latencies, errors, and span details
- Database query timing

### Prometheus — Metrics

If using Docker Compose:
```bash
open http://localhost:9090
```

**Key metrics to query:**
```
events_submitted_total{status="..."}    # Event submission count
transactions_applied_total{status="..."}  # Transaction count
http_server_requests_seconds_*          # Request latency histograms
jvm_memory_used_bytes                   # JVM memory usage
```

### Structured Logs

Both services output JSON logs in ECS format:

```bash
# View logs
docker-compose logs eventsService
docker-compose logs accountService

# Filter by trace ID
docker-compose logs eventsService | grep '"trace.id":"<trace-id>"'
```

**Log fields:**
- `@timestamp` — ISO 8601 timestamp
- `service.name` — eventsService or accountService
- `log.level` — DEBUG, INFO, WARN, ERROR
- `trace.id` — OpenTelemetry trace ID
- `span.id` — OpenTelemetry span ID

## 🛡️ Resiliency Pattern: Circuit Breaker

### Why Circuit Breaker?

The Event Gateway must call the Account Service for every transaction. If Account Service becomes unavailable (network issues, overload, crashes), the Gateway could:
- Waste resources waiting for timeouts
- Overwhelm the Account Service with retries
- Process duplicate events when retrying

### How It Works

**Circuit States:**
1. **CLOSED** (normal operation)
   - Calls go through normally
   - Success/failure tracked

2. **OPEN** (service degraded)
   - After 50% failures in 10-call window, circuit opens
   - New requests fail fast with 503 (no actual call made)
   - Prevents cascading failures
   - Stays open for 30 seconds

3. **HALF_OPEN** (recovery test)
   - After 30 seconds in OPEN state, allows 3 test calls
   - If tests succeed, circuit closes (back to normal)
   - If tests fail, circuit reopens

### Configuration

```properties
# resilience4j.circuitbreaker.instances.accountService.*
sliding-window-type=COUNT_BASED          # Track last N calls
sliding-window-size=10                   # Count last 10 calls
failure-rate-threshold=50                # Open at 50% failures
wait-duration-in-open-state=30s          # Wait 30s before testing
permitted-number-of-calls-in-half-open-state=3  # Test 3 calls
```

### User Experience

- **Service healthy**: Instant response (< 50ms typically)
- **Service degraded**: Fast 503 error (after 50% failures), not waiting for timeout
- **Service recovers**: Automatic recovery after 30 seconds
- **Event safety**: Events NOT saved until Account Service confirms (no replays)

## 🏃 Running Tests

### Test Both Services

**Option 1: Build and test individually**
```bash
cd eventsService && ./mvnw test
cd ../accountService && ./mvnw test
```

**Option 2: Build from root**
```bash
(cd eventsService && ./mvnw clean package)
(cd accountService && ./mvnw clean package)
```

### Current Test Status

⚠️ **Note**: Only placeholder tests exist currently. Test scenarios above should be used to verify functionality and as a template for comprehensive automated tests.

## 🔧 Troubleshooting

### Services can't communicate (local dev)

**Issue**: `Account Service is unreachable`

**Solution**: Ensure using `http://localhost:8081`, not `http://accountService:8081`

Check: `eventsService/src/main/resources/application.properties`
```properties
account.service.base-url=http://localhost:8081
```

### No traces in Zipkin

**Issue**: Zipkin shows no traces

**Solution**:
1. Verify all containers running: `docker-compose ps`
2. Check OTel Collector reachable: `curl http://localhost:4318/v1/traces`
3. Verify endpoint configured: `management.otlp.tracing.endpoint=http://localhost:4318/v1/traces`
4. Submit events to generate traces

### Circuit breaker stuck open

**Issue**: Circuit not recovering

**Solution**:
- Verify Account Service running: `curl http://localhost:8081/health-check`
- Restart eventsService to reset state
- Wait 30+ seconds for HALF_OPEN state

### Port already in use

**Issue**: `Address already in use`

**Solution**:
```bash
# Find and kill process on port
lsof -i :8080 && kill -9 <PID>
lsof -i :8081 && kill -9 <PID>
```

## 📁 Project Structure

```
event-ledger-system/
├── README.md                          # This file
├── IMPLEMENTATION_STATUS.md           # Requirement tracking
├── RUN.md                            # Detailed testing guide
├── CLAUDE.md                         # Developer documentation
├── docker-compose.yml                # Full stack
├── otel-collector-config.yaml        # OpenTelemetry config
├── prometheus.yml                    # Prometheus config
│
├── eventsService/                    # Event Gateway (Port 8080)
│   ├── pom.xml
│   ├── Dockerfile
│   ├── src/main/java/com/example/eventsService/
│   │   ├── controller/               # REST endpoints
│   │   ├── service/                  # Business logic
│   │   ├── client/                   # Account Service client
│   │   ├── repository/               # Data access
│   │   ├── model/                    # Entities & DTOs
│   │   └── exception/                # Error handling
│   └── src/test/java/                # Tests
│
└── accountService/                   # Account Service (Port 8081)
    ├── pom.xml
    ├── Dockerfile
    ├── src/main/java/com/example/accountService/
    │   ├── controller/               # REST endpoints
    │   ├── service/                  # Business logic
    │   ├── repository/               # Data access
    │   ├── model/                    # Entities & DTOs
    │   └── exception/                # Error handling
    └── src/test/java/                # Tests
```

## 📚 Key Technologies

- **Java 21** — Latest LTS version
- **Spring Boot 4.0.6** — Modern framework
- **Spring Data JPA** — Database access
- **H2 Database** — In-memory, separate instances per service
- **Resilience4j 2.2.0** — Circuit breaker pattern
- **OpenTelemetry** — Distributed tracing
- **Prometheus** — Metrics
- **Zipkin** — Trace visualization
- **Docker Compose** — Local orchestration

## 📝 Configuration

### Event Gateway (eventsService) — application.properties

```properties
spring.application.name=eventsService
server.port=8080
account.service.base-url=http://localhost:8081

# Database
spring.datasource.url=jdbc:h2:mem:eventsdb
spring.jpa.hibernate.ddl-auto=create-drop

# Observability
logging.structured.format.console=ecs
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces

# Resilience4j Circuit Breaker
resilience4j.circuitbreaker.instances.accountService.sliding-window-size=10
resilience4j.circuitbreaker.instances.accountService.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.accountService.wait-duration-in-open-state=30s
```

### Account Service (accountService) — application.properties

```properties
spring.application.name=accountService
server.port=8081

# Database
spring.datasource.url=jdbc:h2:mem:accountsdb
spring.jpa.hibernate.ddl-auto=create-drop

# Observability
logging.structured.format.console=ecs
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces
```

## 🚀 Next Steps

1. **Write integration tests** for all test scenarios
2. **Add rate limiting** to prevent abuse
3. **Implement async fallback** to queue events when Account Service down
4. **Replace H2 with PostgreSQL** for production
5. **Add message queue** (Kafka) for async event processing

## 📖 Additional Documentation

- **[CLAUDE.md](CLAUDE.md)** — Developer guide and architecture details
- **[RUN.md](RUN.md)** — Extended testing guide with more scenarios
- **[IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)** — Requirement tracking

## 📞 Support

If anything is unclear:
1. Check [CLAUDE.md](CLAUDE.md) for detailed architecture notes
2. Review [RUN.md](RUN.md) for additional test scenarios
3. Check service logs: `docker-compose logs <service-name>`
4. Inspect traces in Zipkin: http://localhost:9411

## 📄 License

This is a reference implementation for interview/evaluation purposes.

---

**Last Updated**: 2026-06-04  
**Java Version**: 21  
**Spring Boot Version**: 4.0.6  
**Status**: ✅ Feature Complete, ⚠️ Needs Test Automation
