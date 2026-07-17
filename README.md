# System Design + AI Mastery — AESP Learning Journey

> A structured 30-day program to master System Design, AI Integration, Agentic AI, and Autonomous Agents — built around one real enterprise project: the **AI-Powered Enterprise Support Platform (AESP)**.

---

## Why one project?

Instead of learning concepts in isolation, every topic is anchored to AESP — a production-grade customer support automation platform. When you learn caching, you learn *how AESP caches LLM responses in Redis*. When you learn multi-agent systems, you learn *how AESP's Supervisor Agent orchestrates Classifier, RAG, and Resolver agents*. Concepts stick because they live in a real system.

---

## What AESP covers

| Area | Technologies |
|---|---|
| API Gateway & Edge | REST, GraphQL, gRPC, JWT, Rate Limiting |
| Event-Driven Architecture | Apache Kafka, Dead Letter Queues |
| AI Agent Orchestration | ReAct loop, Multi-agent, Tool use |
| RAG Pipeline | Embeddings, Pinecone, ANN Search |
| Microservices | Node.js services, Istio service mesh |
| Data Layer | PostgreSQL (sharded), Redis, Elasticsearch, S3 |
| Infrastructure | Kubernetes, Prometheus, Jaeger, ELK |
| Security | mTLS, Zero Trust, Secrets management |

---

## Roadmap — 30 Days to Senior Level

### Phase 1 — System Design Foundations (Days 1–8)
| Day | Topic | AESP Component |
|---|---|---|
| 1 | APIs — REST, GraphQL, gRPC | Gateway layer |
| 2 | Load balancing + Rate limiting | Edge / Redis |
| 3 | Caching strategies | Redis cache layer |
| 4 | Databases — SQL, NoSQL, Sharding | PostgreSQL + MongoDB |
| 5 | Message queues + Event-driven arch | Kafka event bus |
| 6 | Microservices patterns | Service mesh |
| 7 | CAP theorem + Consistency | Distributed data layer |
| 8 | Phase 1 mock interview | Full ticketing system design |

### Phase 2 — AI Integration + RAG (Days 9–16)
| Day | Topic | AESP Component |
|---|---|---|
| 9 | LLM fundamentals | All AI agents |
| 10 | Prompt engineering | Agent system prompts |
| 11 | Embeddings + Vector DBs | Pinecone / pgvector |
| 12 | RAG pipeline deep dive | RAG Agent |
| 13 | Tool use + Function calling | Agent tool registry |
| 14 | Guardrails + Reliability | Output validation layer |
| 15 | LLM ops — cost + latency | Semantic cache |
| 16 | Phase 2 mock interview | Design a RAG search system |

### Phase 3 — Agentic + Autonomous AI (Days 17–22)
| Day | Topic | AESP Component |
|---|---|---|
| 17 | Agent architectures (ReAct, Plan-Execute) | Supervisor Agent |
| 18 | Multi-agent systems | Agent orchestration |
| 19 | Agent memory systems | Short + long-term memory |
| 20 | Human-in-the-loop design | Escalation Agent |
| 21 | Failure modes + Safety | Guardrails, sandboxing |
| 22 | Phase 3 mock interview | Design an autonomous agent |

### Phase 4 — Scale, Reliability + Observability (Days 23–27)
| Day | Topic | AESP Component |
|---|---|---|
| 23 | Observability — traces, logs, metrics | Jaeger, ELK, Prometheus |
| 24 | Kubernetes + containers | AESP deployment |
| 25 | Resilience patterns | Circuit breaker, Istio |
| 26 | Multi-region + Disaster recovery | Active-active setup |
| 27 | Security architecture | Zero trust, mTLS |

### Phase 5 — Advanced + Interview Mastery (Days 28–30)
| Day | Topic | AESP Component |
|---|---|---|
| 28 | Low-level design — SOLID, patterns | AESP class diagrams |
| 29 | Estimation + capacity planning | AESP at 10M users |
| 30 | Full mock interview | End-to-end AESP design |

---

## Repository Structure

```
system-design-ai-mastery/
├── README.md                    ← You are here
├── architecture/
│   ├── high-level-design.md     ← Full AESP HLD
│   ├── low-level-design.md      ← Class diagrams, schemas
│   └── diagrams/                ← Architecture diagrams
├── phase-1-system-design/
│   ├── day-01-apis/
│   ├── day-02-load-balancing/
│   └── ...
├── phase-2-ai-rag/
│   ├── day-09-llm-fundamentals/
│   └── ...
├── phase-3-agentic-ai/
├── phase-4-scale-observability/
├── phase-5-advanced/
└── interview-prep/
    ├── system-design-questions.md
    └── ai-systems-questions.md
```

---

## Progress Tracker

| Phase | Status | Days Completed |
|---|---|---|
| Phase 1 — System Design | 🔄 In Progress | 0 / 8 |
| Phase 2 — AI + RAG | 🔄 In Progress | 6 / 8 |
| Phase 3 — Agentic AI | ⏳ Upcoming | 0 / 6 |
| Phase 4 — Scale & Observability | ⏳ Upcoming | 0 / 5 |
| Phase 5 — Advanced | ⏳ Upcoming | 0 / 3 |

---

## Goal

After 30 sessions:
- ✅ Senior SDE / Staff Engineer interview-ready
- ✅ Able to design complex distributed systems from scratch
- ✅ Deep understanding of RAG, Agentic AI, Autonomous agents
- ✅ Production-grade AESP codebase as portfolio proof

---

*Learning with Claude · Started June 2026*
