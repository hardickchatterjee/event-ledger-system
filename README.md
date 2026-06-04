# Event Ledger System

A distributed, event-driven transaction processing system built with **Java 21** and **Spring Boot 4.0.6**. Processes financial transaction events with guaranteed idempotency, out-of-order tolerance, resilience patterns, and comprehensive observability.

## 🚀 Quick Start

### Option 1: Docker Compose (Recommended)

Start the entire stack with one command:

```bash
cd /Users/hardickchatterjee/Downloads/event-ledger-system
docker-compose up -d
```

This starts:
- **eventsService** (port 8080) — Event Gateway
- **accountService** (port 8081) — Account Service
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
