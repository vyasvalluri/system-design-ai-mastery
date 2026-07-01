# Day 6 — Microservices Patterns: Saga, CQRS, Service Mesh & API Gateway — Interview Questions

> Extracted from `README.md` for standalone review/quizzing. See the full
> day README for the analogy, concept walkthrough, and code.


**Q: "What problem does the Saga pattern solve? Why not use 2PC?"**

> Two-phase commit locks resources across all participating services until a coordinator decides commit or rollback. In a microservices environment this means service A holds a DB lock waiting for service B — if B is slow or down, A blocks all other requests. 2PC also tightly couples all services to the coordinator. Saga replaces the global lock with a sequence of local transactions. Each service commits immediately and publishes an event. If any step fails, compensating transactions undo previous steps in reverse order. No global lock, no coordinator dependency. The tradeoff: eventual consistency — there is a window where the system is partially updated.

**Q: "When would you use CQRS and when would you avoid it?"**

> Use CQRS when read and write patterns are fundamentally different — different data shapes, performance characteristics, and scaling needs. AESP tickets: writes are normalized ACID operations, reads are full-text search across denormalized documents. Also use CQRS when read traffic massively outscales writes — scale the read side (Elasticsearch nodes) independently of the write side (PostgreSQL). Avoid CQRS for simple CRUD — it adds projectors, two data stores, and eventual consistency to manage. The signal: if your team asks "why are our read queries so complex for such simple writes?" CQRS is probably the answer.

**Q: "What is eventual consistency in CQRS and how do you handle it?"**

> After a write commits to PostgreSQL, the projector updates Elasticsearch asynchronously — this takes milliseconds to seconds. During that window, queries return stale data. Handle it with: (1) optimistic UI — update the screen immediately without waiting for the read model; (2) version tokens — include a version in the write response, the query waits until that version is projected; (3) read-your-own-writes — route the creator's first query to the write model for 2 seconds after a write; (4) document it clearly in your API contract: search results may lag up to 5 seconds.

**Q: "What does a service mesh give you that application code cannot?"**

> A service mesh enforces network policy uniformly across all services without developers adding retry, circuit breaker, or mTLS to every client. Additional benefits: mutual TLS between all services (zero-trust — no service can impersonate another), distributed tracing with zero instrumentation (sidecar injects trace headers automatically), real-time per-service-pair metrics (latency, error rates, saturation), and traffic shaping for canary deployments (route 5% of traffic to a new version). The key value is consistency — a developer writing a new service gets all of this for free by deploying into the mesh.

---
