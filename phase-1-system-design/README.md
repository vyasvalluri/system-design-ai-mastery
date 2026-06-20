# Phase 1 — System Design Foundations

**Duration:** Days 1–8  
**Goal:** Master the core building blocks that appear in every senior system design interview.

Every concept is learned through AESP — not in isolation.

---

## Topics

| Day | Topic | Key Concepts | AESP Context |
|---|---|---|---|
| 1 | APIs — REST, GraphQL, gRPC | HTTP methods, status codes, pagination, versioning | AESP Gateway — which protocol for which client |
| 2 | Load balancing + Rate limiting | Round-robin, least-conn, token bucket, sliding window | AESP edge layer, Redis rate limiter |
| 3 | Caching strategies | Write-through, write-back, TTL, eviction, cache invalidation | AESP Redis cache for LLM responses |
| 4 | Databases — SQL, NoSQL, Sharding | ACID, indexing, B-trees, sharding strategies, replication | AESP PostgreSQL sharded by tenant_id |
| 5 | Message queues + Event-driven | Pub/sub, consumer groups, ordering, at-least-once | AESP Kafka event bus |
| 6 | Microservices patterns | Saga, CQRS, service mesh, API gateway pattern | AESP service decomposition |
| 7 | CAP theorem + Consistency | CP vs AP, eventual consistency, quorum, vector clocks | AESP data layer tradeoffs |
| 8 | Mock interview | Full system design end-to-end | Design the AESP ticketing system |

---

## Interview Questions Covered

- Design a URL shortener
- Design Twitter / X
- Design WhatsApp
- Design a rate limiter
- Design a cache system
- Design a ticketing system (Jira / Zendesk)

---

## How to use this folder

Each `day-XX-topic/` folder contains:
- `README.md` — concept explanation with AESP context
- `code/` — implementation examples
- `interview-questions.md` — common questions + model answers
- `diagrams/` — architecture diagrams
