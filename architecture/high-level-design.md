# AESP — High-Level Design

## System Overview

The **AI-Powered Enterprise Support Platform (AESP)** is a multi-tenant SaaS platform that autonomously resolves customer support tickets using a pipeline of specialized AI agents. Human agents are only involved when AI confidence falls below a threshold.

**Scale targets:**
- 10 million tickets/day
- 500K concurrent users
- P99 latency < 200ms for ticket creation
- AI resolution rate > 70% without human intervention
- 99.99% uptime SLA

---

## Architecture Layers

```
┌─────────────────────────────────────────────────────────┐
│                     CLIENT LAYER                        │
│  Web App · Mobile App · Chat Widget SDK · Email · API   │
└────────────────────────┬────────────────────────────────┘
                         │ HTTPS
┌────────────────────────▼────────────────────────────────┐
│              API GATEWAY & EDGE LAYER                   │
│  CDN/WAF · Rate Limiter · Auth/JWT · Load Balancer      │
└────────────────────────┬────────────────────────────────┘
                         │ Internal
┌────────────────────────▼────────────────────────────────┐
│                  EVENT BUS (KAFKA)                      │
│  ticket.created · ticket.updated · agent.action         │
│  escalation.triggered · Dead Letter Queue               │
└────────────────────────┬────────────────────────────────┘
                         │ Consume
┌────────────────────────▼────────────────────────────────┐
│           AI AGENT ORCHESTRATION LAYER                  │
│                                                         │
│  ┌──────────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │  Classifier  │  │   RAG    │  │    Resolver      │  │
│  │    Agent     │  │  Agent   │  │     Agent        │  │
│  └──────────────┘  └──────────┘  └──────────────────┘  │
│  ┌──────────────────────┐  ┌───────────────────────┐    │
│  │   Supervisor Agent   │  │  Agent Memory Store   │    │
│  │  (Orchestrator)      │  │  (Redis + PG)         │    │
│  └──────────────────────┘  └───────────────────────┘    │
│  ┌─────────────────────┐                                 │
│  │  Escalation Agent   │                                 │
│  └─────────────────────┘                                 │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│               MICROSERVICES LAYER                       │
│  Ticket Svc · User Svc · Notification Svc               │
│  Analytics Svc · Billing Svc                            │
│  ─────────────────────────────────────────              │
│  Service Mesh (Istio) — mTLS · circuit breaker          │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│                   DATA LAYER                            │
│  PostgreSQL (sharded) · Redis · Pinecone                │
│  Elasticsearch · S3/Blob storage                        │
│  ─────────────────────────────────────────              │
│  Primary-replica · CQRS · Sharding by tenant_id         │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│           INFRASTRUCTURE & OBSERVABILITY                │
│  Kubernetes · Jaeger/OTEL · Prometheus · ELK · CI/CD   │
│  Multi-region · Auto-scaling · Blue-green deploy        │
└─────────────────────────────────────────────────────────┘
```

---

## Ticket Lifecycle

```
Customer submits ticket
        │
        ▼
API Gateway validates + publishes ticket.created to Kafka
        │
        ▼
Supervisor Agent consumes event
        │
        ├─► Classifier Agent → intent, priority, sentiment, language
        │
        ├─► RAG Agent → embed query → vector search → top-5 chunks
        │
        ├─► Confidence check
        │       ├─ > 85% → Resolver Agent → auto-reply + close ticket
        │       └─ < 85% → Escalation Agent → route to human
        │
        └─► Outcome fed back → re-rank embeddings (feedback loop)
```

---

## Component Responsibilities

### API Gateway
- **CDN (CloudFront):** Static asset caching, geo-routing
- **WAF:** DDoS protection, SQL injection blocking
- **Rate Limiter:** Token bucket per API key (stored in Redis)
- **Auth:** JWT validation, OAuth2 for third-party integrations
- **Load Balancer:** L7 routing, health checks, sticky sessions for WebSocket

### Kafka Event Bus
- **Partitioning:** By `tenant_id` for ordering guarantees per tenant
- **Retention:** 7 days (allows replay for debugging or reprocessing)
- **Consumer groups:** AI pipeline, notification service, analytics — each independent
- **DLQ:** Failed messages after 3 retries → dead letter topic → alert + manual review

### AI Agent Orchestration
See [Agent Architecture](../phase-3-agentic-ai/README.md)

### Data Layer

| Store | Purpose | Key Design Decision |
|---|---|---|
| PostgreSQL | Tickets, users, audit logs | Sharded by `tenant_id`, read replicas for analytics |
| Redis | Cache, sessions, rate limit counters, agent working memory | TTL-based eviction, Cluster mode |
| Pinecone | Vector embeddings for RAG | Namespaced by `tenant_id`, metadata filtering |
| Elasticsearch | Full-text search, ticket search | Separate cluster, async indexed from Kafka |
| S3 | Attachments, exports, ML model artifacts | Lifecycle policies, pre-signed URLs |

---

## Key Design Decisions

### 1. Why Kafka over direct service calls?
Decouples producers from consumers. If the AI pipeline is slow, tickets queue up in Kafka rather than causing API timeouts. Enables replay, fan-out to multiple consumers, and independent scaling.

### 2. Why shard PostgreSQL by tenant_id?
Prevents one large customer's query load from degrading other tenants. Each shard can be independently scaled or migrated. Simplifies data isolation for compliance (GDPR, SOC2).

### 3. Why CQRS?
Write path (create/update ticket) and read path (analytics dashboard, search) have very different access patterns. CQRS lets us optimize each independently — writes go to primary PG, reads go to Elasticsearch or read replicas.

### 4. Why separate vector DB (Pinecone)?
PostgreSQL with pgvector works for small datasets but Pinecone handles billions of vectors with sub-10ms ANN search at scale. Filtered search by `tenant_id` keeps retrieval isolated per customer.

### 5. Why service mesh (Istio)?
Gives us mTLS between all services without code changes, circuit breaking, retries, and distributed tracing — all at the infrastructure level.

---

## Non-Functional Requirements

| Requirement | Target | How |
|---|---|---|
| Availability | 99.99% | Multi-region active-active, health checks |
| Latency (P99) | < 200ms ticket create | Redis cache, async AI pipeline |
| Throughput | 10M tickets/day (~115 TPS avg, 1000 TPS peak) | Kafka, horizontal scaling |
| AI resolution | > 70% auto-resolved | RAG quality, confidence tuning |
| Data isolation | Per-tenant | Shard key, Pinecone namespaces, row-level security |
| Recovery | RPO < 1min, RTO < 5min | Multi-region replication, automated failover |
