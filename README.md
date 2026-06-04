# Event Ledger System

A distributed, event-driven transaction processing system built with **Java 21** and **Spring Boot 4.0.6**. Processes financial transaction events with guaranteed idempotency, out-of-order tolerance, resilience patterns, and comprehensive observability.

## Architecture Overview

The system consists of two independent microservices that communicate via REST:

**Event Gateway API (eventsService) - Port 8080**
- Public REST API that receives transaction events from clients
- Validates and stores events with idempotency checks using `eventId` as natural primary key
- Forwards transactions to Account Service with circuit breaker protection
- Orders events by `eventTimestamp` (business time) rather than arrival order
- Returns 503 gracefully when Account Service is unavailable

**Account Service (accountService) - Port 8081**
- Internal service that processes transactions and manages account state
- Auto-creates accounts on first transaction
- Deduplicates transactions using UNIQUE constraint on `eventId`
- Computes balances at query time as sum of all transactions (CREDITs - DEBITs)
- Each service has its own H2 in-memory database

**Data Flow:** Client → Event Gateway (validates, deduplicates) → Account Service (applies transaction, updates balance) → Response to client

## Setup Instructions

### Prerequisites

- **Java 21** - `java -version`
- **Docker & Docker Compose** - for containerized deployment (optional but recommended)
- **Maven** - included via `./mvnw` wrapper

### Install Dependencies

No global installation needed. Each service uses Maven wrapper:

```bash
# Build eventsService
cd eventsService
./mvnw clean package

# Build accountService
cd ../accountService
./mvnw clean package
```

## How to Start Both Services

### Option 1: Docker Compose (Recommended)

Start entire stack with observability tools:

```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system
docker-compose up -d
```

This starts:
- **eventsService** - http://localhost:8080
- **accountService** - http://localhost:8081
- **Zipkin** - http://localhost:9411 (trace visualization)
- **OTel Collector** - ports 4317/4318 (trace collection)
- **Prometheus** - http://localhost:9090 (metrics)

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

Services available at:
- eventsService: http://localhost:8080
- accountService: http://localhost:8081

## How to Run the Tests

```bash
# Run eventsService tests
cd /Users/hardickchatterjee/Downloads/event-ledger-system/eventsService
./mvnw test

# Run accountService tests
cd /Users/hardickchatterjee/Downloads/event-ledger-system/accountService
./mvnw test
```

Tests cover:
- Idempotency (duplicate submissions)
- Out-of-order event handling
- Balance computation
- Input validation
- Graceful degradation

## Resiliency Pattern: Circuit Breaker

**Why Circuit Breaker?**

The Event Gateway must call the Account Service for every transaction. Without resilience, failures cascade:
- Network timeouts waste resources waiting
- Retries overwhelm a failing service
- Duplicate processing occurs on retries

**How It Works:**

The circuit breaker wraps the Account Service call and operates in three states:

1. **CLOSED (Normal Operation)**
   - All requests pass through to Account Service
   - Success/failure tracked in a sliding window (10 calls)

2. **OPEN (After Failure Threshold)**
   - When 50% of calls fail within the window, circuit opens
   - New requests immediately return 503 Service Unavailable (fail fast)
   - Stays open for 30 seconds without calling Account Service
   - Saves resources and prevents cascading failures

3. **HALF_OPEN (Testing Recovery)**
   - After 30 seconds, circuit allows 3 test calls through
   - If tests succeed, circuit CLOSES (back to normal)
   - If tests fail, circuit REOPENS (waits another 30 seconds)

**Result:**
- Clients get fast 503 responses instead of hanging timeouts
- Downstream service isn't overwhelmed with retries
- System automatically recovers when service is healthy again
- Events are never saved until Account Service confirms (prevents replays)

**Configuration:**
- Failure threshold: 50% in 10-call window
- Open duration: 30 seconds
- Half-open test calls: 3 calls allowed
