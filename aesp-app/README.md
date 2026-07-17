# AESP — Reference Implementation

Real, buildable Spring Boot code backing the `system-design-ai-mastery`
curriculum. Where the `phase-1-system-design/` and `phase-2-ai-rag/` docs
teach a concept with an illustrative snippet, this project is where that
concept actually gets wired together into one running system.

**Status: Phase 1 (Days 1–8) implemented. Phase 2 (Days 9–16, the AI/RAG
layer) is the next module to build — see "What's not here yet" below.**

## Running it locally

```bash
docker compose up -d          # Postgres, Redis, Kafka
cd aesp-ticket-service && mvn spring-boot:run   # port 8081
cd aesp-gateway && mvn spring-boot:run          # port 8080
```

Then, through the gateway (which applies Day 2's per-tenant rate limiting):

```bash
curl -X POST http://localhost:8080/api/v1/tickets \
  -H "X-Tenant-Id: tenant-acme" \
  -H "Content-Type: application/json" \
  -d '{"subject":"Can'\''t log in","body":"Forgot my password","channel":"EMAIL","idempotencyKey":"msg-123"}'
```

This hasn't been compiled in the sandbox that generated it (no Maven/network
there) — run `mvn clean verify` locally as the first sanity check before
trusting it further.

## Module → curriculum day map

| Module | Curriculum days | What it demonstrates |
|---|---|---|
| `aesp-events` | Day 5 | Shared Kafka event contracts (`TicketCreatedEvent`, `TicketStatusChangedEvent`) so producer/consumer schemas can't silently diverge |
| `aesp-ticket-service` | Day 1, 3, 4, 5, 6, 7 | REST API design + 202-Accepted async pattern (1), Redis cache-aside on reads (3), tenant-aligned indexing + Flyway migration (4), async event publish on write (5), CQRS write/read split + simplified Saga step (6), CP-by-design assignment write vs. AP analytics stream (7) |
| `aesp-gateway` | Day 1, 2 | Edge routing, per-tenant Redis token-bucket rate limiting (not per-IP — see `RateLimitConfig`) |
| `docs/adr/` | Day 6, 16 | Architecture Decision Records — including one (`0002`) that's honest about a simplification made and what the "real" version would require |

Day 2's load-balancing side (as opposed to rate limiting) and Day 8's mock
interview are concept-only by nature — a single-machine reference
implementation doesn't have multiple instances to load-balance across, and
Day 8 is itself a synthesis exercise, not a component. Both are covered in
`phase-1-system-design/`.

## What's not here yet

Phase 2 (Days 9–16) needs its own module — a `aesp-rag-service` covering
embeddings/vector search (Day 11), the RAG pipeline (Day 12), tool use (Day
13), guardrails (Day 14), and the semantic cache + model router (Day 15) —
plus wiring the `TicketCreatedEvent` from `aesp-events` into that service as
its ingestion trigger, since Day 16's mock-interview design assumed exactly
that event-driven freshness path. That's the next build.

## Design decisions worth reading before extending this

- `docs/adr/0001-multi-module-maven.md` — why this is a Maven reactor of
  independently-deployable services, not one monolithic module
- `docs/adr/0002-saga-vs-orchestrator.md` — where the current ticket
  assignment logic is a simplified stand-in for a real Saga, and what
  extracting a second service to complete that pattern would look like
