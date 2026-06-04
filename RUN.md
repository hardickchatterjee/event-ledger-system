# Event Ledger System — Running and Testing Guide

## Project Status

### ✅ What's Working

The Event Ledger system is **fully implemented** with the following features:

#### Core Functionality
- ✅ **Idempotency** — Duplicate `eventId` submissions return existing event without re-processing
- ✅ **Out-of-order tolerance** — Events ordered by `eventTimestamp`, not insertion order; balances always correct
- ✅ **Balance computation** — Real-time sum of CREDIT/DEBIT transactions
- ✅ **Input validation** — Rejects missing fields, invalid amounts, unknown event types
- ✅ **Service separation** — Two independent services with separate H2 databases

#### Resilience & Observability
- ✅ **Circuit Breaker** — Resilience4j on eventsService → accountService calls (50% failure threshold)
- ✅ **Graceful degradation** — 503 Service Unavailable when Account Service is down (not 500)
- ✅ **Distributed tracing** — OpenTelemetry with W3C `traceparent` header propagation
- ✅ **Structured logging** — JSON logs in ECS format with trace IDs
- ✅ **Health checks** — Custom `/health-check` endpoints on both services
- ✅ **Metrics** — Prometheus counters for events and transactions

#### Deployment
- ✅ **Docker Compose** — Full stack with observability (Zipkin, OTel Collector, Prometheus)
- ✅ **Local development** — Can run services standalone with `./mvnw spring-boot:run`

### ⚠️ What Needs Testing

While the implementation is complete, **comprehensive test coverage is missing**. Currently only placeholder tests exist:

- ❌ **Idempotency tests** — Verify duplicate submissions are handled correctly
- ❌ **Out-of-order tests** — Verify events are ordered by `eventTimestamp`
- ❌ **Balance computation tests** — Verify math is correct regardless of order
- ❌ **Resiliency tests** — Simulate Account Service failures, verify circuit breaker behavior
- ❌ **Trace propagation tests** — Verify trace IDs flow from Gateway to Account Service
- ❌ **Graceful degradation tests** — Verify 503 responses when Account Service is down
- ❌ **Integration tests** — Full Gateway → Account Service flow

---

## Quick Start

### Option 1: Docker Compose (Recommended for full stack)

Start all services with a single command:

```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system
docker-compose up -d
```

This starts:
- **eventsService** (port 8080) — Event Gateway
- **accountService** (port 8081) — Account processor
- **Zipkin** (port 9411) — Trace visualization
- **OTel Collector** (ports 4317/4318) — Trace collection
- **Prometheus** (port 9090) — Metrics dashboard

### Option 2: Local Development (Two Terminals)

Terminal 1 — eventsService:
```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system/eventsService
./mvnw spring-boot:run
```

Terminal 2 — accountService:
```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system/accountService
./mvnw spring-boot:run
```

---

## Test Scenarios

### 1. Idempotency Test

**Objective:** Verify duplicate submissions are handled correctly without double-charging.

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

**Expected response (200 OK):**
```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 100.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T09:00:00Z",
  "status": "PROCESSED",
  "ingestedAt": "2026-06-04T14:30:00Z"
}
```

**Step 2: Resubmit the same event (same eventId)**
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

**Expected response (200 OK):**
- Same response as Step 1 (not a duplicate of the same data, but the exact same event record)
- No new charge applied

**Step 3: Verify balance**
```bash
curl http://localhost:8081/accounts/acct-123/balance
```

**Expected response:**
```json
{
  "accountId": "acct-123",
  "balance": 100.0000,
  "currency": "USD",
  "transactionCount": 1
}
```

✅ **Success**: Balance is 100, not 200 (no double-charge)

---

### 2. Out-of-Order Event Handling Test

**Objective:** Verify events can arrive out of order and are returned in correct chronological order.

**Step 1: Submit first event (later timestamp)**
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

**Step 2: Submit second event (earlier timestamp — arriving out of order)**
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

**Expected response (ordered by eventTimestamp ASC):**
```json
[
  {
    "eventId": "evt-003",
    "accountId": "acct-456",
    "type": "DEBIT",
    "amount": 50.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z",
    "status": "PROCESSED"
  },
  {
    "eventId": "evt-002",
    "accountId": "acct-456",
    "type": "CREDIT",
    "amount": 200.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T10:00:00Z",
    "status": "PROCESSED"
  }
]
```

**Step 4: Verify balance is correct**
```bash
curl http://localhost:8081/accounts/acct-456/balance
```

**Expected response:**
```json
{
  "accountId": "acct-456",
  "balance": 150.0000,
  "currency": "USD",
  "transactionCount": 2
}
```

✅ **Success**: Events ordered by timestamp ASC, balance correct (200 - 50 = 150)

---

### 3. Balance Computation Test

**Objective:** Verify balance is correctly computed as sum of CREDITs − sum of DEBITs.

**Step 1: Submit multiple events**
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

**Step 2: Query balance**
```bash
curl http://localhost:8081/accounts/acct-789/balance
```

**Expected response:**
```json
{
  "accountId": "acct-789",
  "balance": 1100.0000,
  "currency": "USD",
  "transactionCount": 4
}
```

✅ **Success**: Balance = (1000 + 500) − (300 + 100) = 1100

---

### 4. Validation Test

**Objective:** Verify invalid inputs are rejected with appropriate HTTP status codes.

**Step 1: Missing required field (no amount)**
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

**Expected response (400 Bad Request):**
```json
{
  "error": "Validation failed",
  "message": "Field 'amount' is required"
}
```

**Step 2: Negative amount**
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

**Expected response (400 Bad Request):**
```json
{
  "error": "Validation failed",
  "message": "Amount must be greater than 0"
}
```

**Step 3: Invalid event type**
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

**Expected response (400 Bad Request):**
```json
{
  "error": "Validation failed",
  "message": "Event type must be 'CREDIT' or 'DEBIT'"
}
```

✅ **Success**: All invalid inputs rejected with 400

---

### 5. Graceful Degradation Test (Account Service Down)

**Objective:** Verify Gateway returns 503 when Account Service is unavailable.

**Step 1: Stop Account Service**
```bash
# If running locally in Terminal 2, press Ctrl+C
# Or if using Docker:
docker-compose stop accountService
```

**Step 2: Submit an event to Gateway**
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

**Expected response (503 Service Unavailable):**
```json
{
  "error": "Service Unavailable",
  "message": "Account Service is currently unavailable. Please try again later."
}
```

**Step 3: Verify event was NOT saved**
```bash
curl http://localhost:8080/events/evt-downtime-1
```

**Expected response (404 Not Found):**
```json
{
  "error": "Not Found",
  "message": "Event not found"
}
```

**Step 4: Restart Account Service**
```bash
# If using Docker:
docker-compose start accountService

# Or Terminal 2:
./mvnw spring-boot:run
```

**Step 5: Resubmit event (should succeed)**
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

**Expected response (200 OK):**
- Event successfully processed

✅ **Success**: Gateway gracefully degrades, returns 503 (not 500), and recovers when service restarts

---

### 6. Circuit Breaker Test

**Objective:** Verify circuit breaker opens after repeated failures.

**Setup:** Start both services normally, then stop Account Service.

**Step 1: Submit events until circuit opens (50% failure in 10-call window)**
```bash
# Submit 5 events rapidly while Account Service is down
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

**Expected response:** All return 503

**Step 2: After circuit opens (50% failures), subsequent calls fail fast**
```bash
# Submit one more event; should get fast 503 without waiting for timeout
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

**Expected:** Very fast response (< 100ms), indicating circuit is OPEN (not waiting for timeout)

**Step 3: Restart Account Service and wait 30+ seconds**
```bash
docker-compose start accountService
sleep 35  # Wait for circuit to attempt recovery
```

**Step 4: Submit event; circuit should recover**
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

**Expected response (200 OK):**
- Event successfully processed after circuit recovers

✅ **Success**: Circuit opens, fails fast, then recovers after service restarts

---

### 7. Distributed Tracing Test

**Objective:** Verify trace IDs propagate from Gateway to Account Service.

**Step 1: Submit an event and capture trace ID**
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

**Expected response headers:**
```
< traceparent: 00-<trace-id>-<span-id>-01
```

**Step 2: Check logs for trace ID**

In eventsService logs:
```
2026-06-04T14:30:00Z eventsService DEBUG: trace.id=<same-trace-id> Processing event evt-trace-1
```

In accountService logs:
```
2026-06-04T14:30:00Z accountService DEBUG: trace.id=<same-trace-id> Applying transaction
```

**Step 3: View full trace in Zipkin (if using Docker Compose)**
```
Navigate to: http://localhost:9411
Search by trace ID or service name
```

**Expected:** Full request path visible with latency breakdown for Gateway → Account Service

✅ **Success**: Trace ID propagates correctly across services

---

## Running Tests

### Run Both Services' Tests

**Option 1: Build and test both services**
```bash
# Test eventsService
cd /Users/hardickchatterjee/Downloads/event-ledger-system/eventsService
./mvnw test

# Test accountService
cd /Users/hardickchatterjee/Downloads/event-ledger-system/accountService
./mvnw test
```

**Option 2: Run all tests from root**
```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system

# Build both services
(cd eventsService && ./mvnw clean package)
(cd accountService && ./mvnw clean package)
```

### Current Test Status

⚠️ Currently only placeholder tests exist (contextLoads). **These need to be expanded** with:
- Idempotency tests
- Out-of-order handling tests
- Balance computation tests
- Resiliency tests
- Circuit breaker behavior tests
- Trace propagation verification tests

---

## Observability

### Zipkin (Trace Visualization)

If using Docker Compose:

```bash
# Open in browser
open http://localhost:9411
```

**Navigation:**
1. Select service name (eventsService or accountService)
2. Click on trace to see full request path
3. Inspect latencies, errors, and span details

**Trace ID lookup:**
- Search by trace ID (from curl response headers)
- Search by service and time range

### Prometheus (Metrics)

If using Docker Compose:

```bash
# Open in browser
open http://localhost:9090
```

**Key metrics to query:**
- `events_submitted_total{status="..."}` — Event submission count
- `transactions_applied_total{status="..."}` — Transaction count
- `http_server_requests_seconds_*` — Request latency histograms
- `jvm_memory_used_bytes` — JVM memory usage

**Example queries:**
```
events_submitted_total
rate(events_submitted_total[5m])
http_server_requests_seconds_bucket{le="0.1"}
```

### Structured Logs

Both services output JSON logs in ECS format:

```bash
# For local deployment, check console output
# For Docker deployment:
docker-compose logs eventsService
docker-compose logs accountService

# Filter by trace ID:
docker-compose logs eventsService | grep '"trace.id":"<trace-id>"'
```

**Log fields:**
- `@timestamp` — ISO 8601 timestamp
- `service.name` — eventsService or accountService
- `log.level` — DEBUG, INFO, WARN, ERROR
- `trace.id` — OpenTelemetry trace ID
- `span.id` — OpenTelemetry span ID
- `message` — Log message

---

## Health Checks

### Gateway Health

```bash
# Custom health check (with service diagnostics)
curl http://localhost:8080/health-check

# Spring Actuator health (detailed)
curl http://localhost:8080/actuator/health
```

### Account Service Health

```bash
# Custom health check
curl http://localhost:8081/health-check

# Spring Actuator health
curl http://localhost:8081/actuator/health
```

---

## Cleanup

### Stop Docker Compose

```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system
docker-compose down
```

### Stop Local Services

Press `Ctrl+C` in each terminal running the services.

---

## Architecture Overview

```
┌─────────────────────────────────┐
│   Client / Test Script          │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│     Event Gateway API (port 8080)       │
│  - Receives events                      │
│  - Idempotency check (eventId)          │
│  - Stores EventRecord                   │
│  - Calls Account Service                │
│  - Circuit breaker protection           │
└──────────────┬────────────────────────┘
               │ (REST + W3C traceparent header)
               ▼
┌──────────────────────────────────────────┐
│    Account Service (port 8081)           │
│  - Deduplicates by eventId               │
│  - Auto-creates accounts                 │
│  - Stores transactions                   │
│  - Computes balance at query time        │
└──────────────────────────────────────────┘
      │         │         │
      ▼         ▼         ▼
┌────────────────────────────────────────────┐
│  Observability Stack (Docker Compose)     │
│  - Zipkin (trace visualization)           │
│  - OTel Collector (trace aggregation)     │
│  - Prometheus (metrics)                   │
│  - Structured logging (ECS)               │
└────────────────────────────────────────────┘
```

---

## Troubleshooting

### Services can't communicate (local dev)

**Issue:** `Account Service is unreachable` error when calling accountService.

**Solution:** Ensure you're using `http://localhost:8081`, not `http://accountService:8081`. The latter is for Docker.

**Check config:** `eventsService/src/main/resources/application.properties`
```properties
account.service.base-url=http://localhost:8081
```

### No traces in Zipkin

**Issue:** Zipkin shows no traces.

**Solution:**
1. Verify `docker-compose ps` shows all containers running
2. Check OTel Collector is reachable: `curl http://localhost:4318/v1/traces`
3. Verify `management.otlp.tracing.endpoint=http://localhost:4318/v1/traces` in properties
4. Submit a few events to generate traces

### Circuit breaker stuck open

**Issue:** Circuit breaker not recovering.

**Solution:**
- Verify Account Service is actually running: `curl http://localhost:8081/health-check`
- Manually restart eventsService to reset circuit state
- Wait 30+ seconds for circuit to transition to HALF_OPEN state

### Port already in use

**Issue:** `Address already in use` when starting services.

**Solution:**
```bash
# Find process on port 8080
lsof -i :8080
# Kill it
kill -9 <PID>

# Find process on port 8081
lsof -i :8081
kill -9 <PID>
```

---

## Next Steps

1. **Write integration tests** — Add comprehensive test coverage for all scenarios
2. **Add more metrics** — Custom metrics for request latency, error rates
3. **Implement async fallback** — Queue events locally when Account Service is down
4. **Add rate limiting** — Prevent abuse on Gateway endpoints
5. **Persistent database** — Replace H2 with PostgreSQL for production
6. **Message queue** — Use Kafka for async event processing

---

**Last Updated:** 2026-06-04  
**System Status:** ✅ Fully implemented, ⚠️ needs comprehensive test coverage  
**Docker Status:** ✅ Ready for deployment  
**Observability:** ✅ Zipkin, Prometheus, structured logging integrated
