# Day 8 — Mock System Design Interview: "Design AESP"

**Phase 1 capstone.** Every prior day (APIs, load balancing, databases, message
queues, Saga/CQRS/service mesh, CAP theorem) gets used here. Unlike Days 1–7,
there's no separate "concept" section — the entire day *is* the applied
exercise. Treat this as a 45-minute interview loop you'd get at an FDE or AI
Platform Engineer onsite, run against your own project.

---

## How to run this day

1. Set a **45-minute timer**. Don't peek at the model answer until you've
   written your own on paper/whiteboard first.
2. Read only the **prompt** below — not the requirements list — and start by
   asking clarifying questions out loud, the way you would to an interviewer.
3. After your attempt, compare against the walkthrough.
4. Do the **interview Q&A** section as a second pass — cover the answers and
   quiz yourself.
5. Finish with the **Phase 1 retrospective** at the bottom — that's what goes
   into your portfolio README and LinkedIn post.

---

## The Prompt

> "Design AESP — an AI-Powered Enterprise Support Platform that ingests
> support tickets from multiple channels (email, chat, web form), uses an LLM
> to triage/summarize/suggest resolutions, routes tickets to the right queue
> or human agent, and gives engineering teams analytics on ticket trends.
> Assume enterprise scale: think Zendesk/Salesforce Service Cloud, but with
> an AI layer. You have 45 minutes."

This is intentionally close to home — it's your actual curriculum anchor
project, so the mock interview and your real build reinforce each other.

---

## Step 1 — Clarifying Questions (first 3–5 minutes)

A candidate who jumps straight to boxes-and-arrows without scoping loses
points. Ask things like:

- **Scale**: How many tickets/day? 10K? 1M? (Assume: **500K tickets/day**,
  ~6 tickets/sec average, bursty up to 50/sec at peak.)
- **Latency**: Does AI triage need to be synchronous (blocking the UI) or can
  it be async? (Assume: **async** — ticket is accepted immediately,
  AI enrichment happens in the background within ~10 seconds.)
- **Consistency**: If two agents view the same ticket simultaneously, does
  everyone need to see the exact same state instantly? (Assume:
  **eventual consistency** is fine for analytics/search; ticket
  status/assignment needs **strong consistency** to avoid double-assignment.)
- **Multi-tenancy**: Is this single enterprise or SaaS serving many
  enterprises? (Assume: **multi-tenant SaaS**, tenant isolation required.)
- **LLM dependency**: What happens if the LLM provider is slow/down? (Assume:
  ticket flow must **degrade gracefully** — ticket still gets created and
  queued even if AI enrichment fails or times out.)

This alone signals seniority — it's exactly what Day 7's CAP framing trained
you to probe for.

---

## Step 2 — Functional & Non-Functional Requirements

**Functional**
- Ingest tickets from email, chat widget, web form (3 channels → common schema)
- AI triage: category, priority, sentiment, suggested resolution/KB article
- Route ticket to correct queue/agent based on triage + rules
- Agents can view, update, comment, resolve tickets
- Analytics dashboard: volume trends, SLA breach rates, category breakdown

**Non-Functional**
- Availability: 99.9% (ticket ingestion must never be the bottleneck)
- Latency: ticket creation ack < 200ms; AI enrichment < 10s (async, non-blocking)
- Durability: no ticket ever lost, even if AI/analytics pipeline is down
- Scalability: 500K tickets/day baseline, headroom to 5M
- Multi-tenant isolation at data layer

---

## Step 3 — Capacity Estimation (back-of-envelope)

```
500,000 tickets/day
≈ 6 tickets/sec average
Peak (3-5x average, typical for support platforms) ≈ 25-30 tickets/sec

Ticket payload ≈ 5 KB (text + metadata, before attachments)
Daily ingestion ≈ 500,000 × 5 KB ≈ 2.5 GB/day of raw ticket text
Attachments (assume 20% of tickets, avg 500 KB) ≈ 50 GB/day → object storage, not DB

AI enrichment calls: 500,000/day ≈ 6/sec average, 30/sec peak
  → needs a queue + worker pool, NOT synchronous inline calls

Read:write ratio for support platforms is read-heavy (agents/dashboards
re-read tickets repeatedly) → estimate 10:1 read:write
```

This number-first instinct is exactly what Day 4 (indexing/sharding) and
Day 1 (API design) primed you for — interviewers want to see you reason from
numbers, not vibes.

---

## Step 4 — High-Level Architecture

```
                                   ┌──────────────────┐
 [Email] [Chat] [Web Form] ──────▶│   API Gateway      │  (Day 1: REST ingestion API,
                                   │   (rate limit,     │   auth, versioning)
                                   │    auth, routing)  │
                                   └─────────┬─────────┘
                                             │
                                   ┌─────────▼─────────┐
                                   │  Load Balancer      │  (Day 2: L7, health checks,
                                   │  → Ticket Service    │   round-robin/least-conn)
                                   └─────────┬─────────┘
                                             │
                        ┌────────────────────┼────────────────────┐
                        │                    │                    │
              ┌─────────▼────────┐ ┌─────────▼────────┐ ┌────────▼─────────┐
              │  Ticket Service    │ │  Message Queue     │ │  Ticket DB        │
              │  (writes ticket,   │─▶  (Kafka/SQS)       │ │  (Day 4: sharded  │
              │   returns 202 ack) │ │  Day 5: async       │ │   by tenant_id,   │
              └───────────────────┘ │  decoupling         │ │   replicated for  │
                                     └─────────┬──────────┘ │   read scaling)   │
                                               │             └───────────────────┘
                        ┌──────────────────────┼──────────────────────┐
                        │                      │                      │
              ┌─────────▼────────┐  ┌──────────▼─────────┐  ┌────────▼─────────┐
              │  AI Triage Worker  │  │  Routing Worker      │  │  Analytics Worker │
              │  (calls LLM,       │  │  (CQRS command:      │  │  (CQRS query side:│
              │   writes back      │  │   assign to queue/   │  │   builds read     │
              │   enrichment)      │  │   agent, Saga step)   │  │   models for      │
              └───────────────────┘  └──────────────────────┘  │   dashboards)     │
                                                                 └───────────────────┘
```

**Why each Day-1-through-7 concept shows up here:**

| Component | Concept from | Why |
|---|---|---|
| API Gateway | Day 1 (APIs) | Single entry point, auth, versioning, rate limiting per tenant |
| Load Balancer | Day 2 | Ticket Service is stateless → horizontally scaled behind LB |
| Sharded/replicated Ticket DB | Day 4 | Shard by `tenant_id` for isolation + scale; read replicas for the 10:1 read-heavy load |
| Kafka/SQS queue | Day 5 | Decouples ingestion (must be fast, 200ms) from AI enrichment (slow, 10s, can fail) |
| CQRS split (command vs. analytics read models) | Day 6 | Writes (ticket create/update) go through Ticket Service; analytics dashboard reads from a denormalized read-optimized store built by the Analytics Worker |
| Saga for routing | Day 6 | Routing may involve multiple steps (classify → check agent availability → assign → notify) across services; a Saga keeps this consistent without a distributed transaction |
| CAP trade-off | Day 7 | Ticket status/assignment = **CP** (must be correct, no double-assign); analytics/search = **AP** (fine to be a few seconds stale) |

---

## Step 5 — Deep Dive 1: Why async AI enrichment, not inline?

If the LLM call were synchronous inside the ticket-creation request:
- A slow/down LLM provider directly degrades your 200ms ack SLA — violates
  the availability requirement.
- Retry storms during LLM outages would cascade into the ingestion path.

Instead: Ticket Service writes the ticket (durable, fast), publishes a
`TicketCreated` event to Kafka, returns 202 immediately. The AI Triage
Worker consumes independently, with its own retry/backoff/circuit-breaker,
and writes enrichment data back via a separate update — never blocking
ingestion. This is the Day 5 message-queue pattern applied directly to
the reliability requirement from Step 2.

## Step 6 — Deep Dive 2: Multi-tenant sharding strategy

Shard the Ticket DB by `tenant_id` (Day 4). Rationale:
- Most queries are tenant-scoped (an agent only sees their own org's
  tickets) → shard key aligns with the access pattern, avoids scatter-gather.
- Noisy-neighbor isolation: one enterprise's traffic spike doesn't
  starve another's.
- Trade-off: very large enterprise tenants can still hot-spot a single
  shard — mitigate with sub-sharding by `tenant_id + ticket_id_range` for
  the largest few tenants, same idea as Day 4's hot-partition discussion.

## Step 7 — Deep Dive 3: CAP trade-off in practice

- **Ticket assignment (CP)**: use the primary DB with strong consistency
  for the assignment write — two agents must never be assigned the same
  ticket. Brief unavailability during a partition is acceptable; a wrong
  assignment is not.
- **Analytics dashboard (AP)**: read model can lag by seconds; during a
  partition, serve stale-but-available data rather than blocking the
  dashboard. This directly mirrors the Day 7 framing of choosing CP vs AP
  **per subsystem**, not for the whole platform.

---

## Step 8 — Bottlenecks & Failure Modes (what a strong candidate raises unprompted)

- **LLM provider outage** → circuit breaker on AI Triage Worker; tickets
  still get created and queued for manual triage; enrichment backfills later.
- **Kafka consumer lag** → autoscale worker pool on queue depth; alert if
  lag exceeds SLA-relevant threshold (>10s enrichment target).
- **Hot tenant shard** → sub-sharding, plus per-tenant rate limiting at the
  gateway to protect shared infrastructure.
- **Duplicate ticket ingestion** (e.g., email retries) → idempotency key
  per channel + message ID, dedup at Ticket Service.

---

## Interview Q&A (cover the answers, quiz yourself)

**Q1: Why not just call the LLM synchronously and simplify the architecture?**
A: It couples a fast, must-be-reliable path (ticket creation) to a slow,
sometimes-unreliable dependency (LLM). Async via a queue isolates failure
domains and lets you meet the 200ms ack SLA independent of LLM latency.

**Q2: How would you handle a tenant that suddenly sends 100x their normal volume?**
A: Rate limit at the API Gateway per tenant; queue absorbs bursts (Day 5);
autoscale workers on queue depth; if a shard is affected, that's the
sub-sharding mitigation from Deep Dive 2.

**Q3: Why CQRS here instead of just querying the primary DB for analytics too?**
A: Analytics queries (aggregations across large ticket volumes) have very
different access patterns than transactional ticket read/writes — running
both on the primary DB creates contention and forces a schema that's bad
at both jobs. A separate read-optimized store built by consuming the same
event stream avoids that, at the cost of eventual consistency, which is
acceptable per the requirements gathered in Step 1.

**Q4: What's the actual Saga here, step by step?**
A: (1) Ticket triaged → (2) check target queue/agent availability → (3)
assign ticket → (4) notify agent. If step 3 fails (agent went offline
between check and assign), compensating action reroutes to next available
agent rather than leaving the ticket in limbo — classic choreography-based
Saga from Day 6.

**Q5: Where's the single point of failure in this design, and how do you remove it?**
A: Naively, the API Gateway or LB could be a SPOF — mitigate with multiple
LB instances behind DNS/anycast and gateway instances behind the LB itself
(Day 2 pattern). The message queue (Kafka) is made durable via replication
across brokers.

**Q6: If you had to cut scope for a 45-minute interview, what would you defer?**
A: Multi-region failover, fine-grained per-tenant SLA tiers, and the exact
LLM prompt/fallback-model chain — mention them as "future work" rather than
designing live, to protect time for the core data flow and consistency
story, which is what's actually being evaluated.

---

## Self-Evaluation Checklist

- [ ] Asked clarifying questions before designing (scale, latency, consistency)
- [ ] Gave rough capacity numbers before drawing boxes
- [ ] Justified async processing with a concrete SLA reason, not just "it's better"
- [ ] Applied CAP theorem **per subsystem**, not as a single platform-wide choice
- [ ] Named at least one real bottleneck and its mitigation, unprompted
- [ ] Could trace every box in the diagram back to a requirement from Step 2
- [ ] Kept the whole walkthrough inside ~40 minutes, leaving time for Q&A

---

## Phase 1 Retrospective (Days 1–8)

| Day | Topic | Where it showed up today |
|---|---|---|
| 1 | APIs | API Gateway design, versioning, tenant auth |
| 2 | Load Balancing | Stateless Ticket Service behind LB |
| 3 | (foundations) | Underpins the whole request lifecycle |
| 4 | Databases: Indexing, Sharding, Replication | Tenant-sharded DB, read replicas |
| 5 | Message Queues & Event-Driven Architecture | Async AI enrichment decoupling |
| 6 | Saga, CQRS, Service Mesh | Routing Saga, analytics CQRS split |
| 7 | CAP theorem & consistency | Per-subsystem CP vs AP decisions |
| 8 | Mock interview (this doc) | Synthesis — all of the above under time pressure |

**Portfolio note**: this document is a strong candidate for the repo's
`docs/adr/` or a top-level `SYSTEM-DESIGN.md` — it's already close to the
Spring Boot + ADR + Docker Compose direction planned for Phase 2, and it's
concrete enough to link directly in a LinkedIn post about the AESP build.

**Outstanding from the curriculum**: retroactively add
`interview-questions.md` to Days 1–7 folders — Day 8 already has its Q&A
built in above, so that file for Day 8 can just be a lift of the "Interview
Q&A" section into its own file for consistency with the other days' folder
structure.
