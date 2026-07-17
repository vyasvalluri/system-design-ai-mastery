# Day 16 — Mock Interview: "Design a RAG Search System"

**Phase 2 capstone.** Every prior day (LLM fundamentals, prompt engineering,
embeddings/vector DBs, RAG pipeline, tool use, guardrails, LLM ops) gets used
here. Like Day 8, there's no separate "concept" section — the entire day *is*
the applied exercise. Treat this as a 45-minute interview loop you'd get at
an AI Platform Engineer or FDE onsite, run against your own project.

---

## How to run this day

1. Set a **45-minute timer**. Don't peek at the model answer until you've
   written your own on paper/whiteboard first.
2. Read only the **prompt** below — not the requirements list — and start by
   asking clarifying questions out loud, the way you would to an interviewer.
3. After your attempt, compare against the walkthrough.
4. Do the **interview Q&A** section as a second pass — cover the answers and
   quiz yourself.
5. Finish with the **Phase 2 retrospective** at the bottom.

---

## The Prompt

> "Design the RAG search system inside AESP — the component that lets a
> support agent (or the AI agent itself) ask a natural-language question and
> get back an accurate, grounded answer pulled from your knowledge base:
> past tickets, KB articles, product docs, and runbooks, across potentially
> thousands of enterprise tenants. Assume this feeds both a customer-facing
> chat assistant and an internal agent-assist panel. You have 45 minutes."

This is intentionally close to home — it's the RAG Agent your AESP curriculum
has been building toward since Day 9.

---

## Step 1 — Clarifying Questions (first 3–5 minutes)

A candidate who jumps straight to boxes-and-arrows without scoping loses
points. Ask things like:

- **Scale**: How large is the knowledge base per tenant, and how many tenants?
  (Assume: **2,000 tenants**, average **50K documents/tenant** — tickets, KB
  articles, docs — totaling **~100M documents** platform-wide.)
- **Query volume & latency**: How many queries/sec, and what's the acceptable
  response time? (Assume: **200 QPS average, 800 QPS peak**; target
  **p95 end-to-end latency under 2.5s** including retrieval + generation.)
- **Freshness**: How quickly must newly created/updated tickets and KB
  articles become searchable? (Assume: **near-real-time for tickets** — under
  1 minute — since agents reference very recent tickets; **KB article
  updates can tolerate up to 15 minutes** of staleness.)
- **Multi-tenancy & isolation**: Must retrieval strictly never leak content
  across tenants? (Assume: **yes, hard isolation requirement** — a retrieval
  leak across tenants is a security incident, not a quality bug.)
- **Consumers**: Does the same retrieval system serve both the customer chat
  assistant and the internal agent-assist panel, or are they separate?
  (Assume: **shared retrieval infrastructure**, but different downstream
  guardrail/tone policies per consumer — internal agents can see more raw
  detail than customers.)
- **Failure behavior**: What happens if retrieval or the LLM is degraded?
  (Assume: must **degrade gracefully** — fall back to keyword search results
  shown as links, or "connect with a human," never a fabricated answer.)

This alone signals seniority — it mirrors the CAP-theorem-style probing from
Day 7, now applied to a retrieval + generation system instead of a plain CRUD
service.

---

## Step 2 — Functional & Non-Functional Requirements

**Functional**
- Ingest and index documents from multiple sources (tickets, KB, docs, runbooks)
- Chunk and embed documents (Day 11), keep the index fresh as sources change
- Given a query, retrieve relevant chunks with strict tenant isolation
- Generate a grounded answer with citations back to source documents (Days 12, 14)
- Serve both a customer chat assistant and an internal agent-assist UI with
  shared retrieval but different guardrail policies
- Provide an "insufficient context" fallback rather than a fabricated answer

**Non-Functional**
- Latency: p95 end-to-end (retrieval + generation) under 2.5s
- Freshness: tickets searchable within 1 minute, KB articles within 15 minutes
- Strict multi-tenant data isolation at every stage (embedding storage, retrieval, caching)
- Availability: 99.9% for the retrieval path; graceful degradation if the LLM is down
- Cost: bounded per-tenant token spend, since this runs on every support query (Day 15)

---

## Step 3 — Capacity Estimation (back-of-envelope)

```
100M documents platform-wide, avg ~800 tokens/doc before chunking
Chunked at ~250 tokens/chunk with structural chunking (Day 14-adjacent) →
  ≈ 100M × 3.2 chunks/doc ≈ 320M chunks to embed and index

Embedding dimension: assume 1536-dim float32 vectors
  320M × 1536 × 4 bytes ≈ 1.97 TB of raw vector data
  → must shard the vector index; single-node ANN index is not viable at this scale

Query volume: 200 QPS avg, 800 QPS peak
  Each query: 1 embedding call + 1 hybrid retrieval (dense+sparse) + 1 re-rank
    + 1 generation call ≈ 4 downstream calls per user query

Ticket ingestion rate (from Day 8's estimate): ~6 tickets/sec avg, ~30/sec peak
  → each new/updated ticket triggers an incremental embed + upsert,
    NOT a full reindex — reindexing 320M chunks per ticket update is a
    non-starter

Semantic cache (Day 15) target: even a 30-40% hit rate on repetitive support
  queries removes a large fraction of the 200 QPS from ever reaching the
  full retrieval + generation path
```

This number-first instinct is exactly what Day 4 (indexing/sharding) and
Day 11 (vector DBs) primed you for — interviewers want to see you reason from
numbers, not vibes, especially the moment vector storage crosses into
terabyte territory.

---

## Step 4 — High-Level Architecture

```
                         ┌───────────────────────┐
[Chat Assistant] ───────▶│                        │
[Agent-Assist Panel] ───▶│    Query Gateway        │  (Day 1: auth, tenant
                         │  (per-consumer policy)  │   context, rate limit)
                         └───────────┬────────────┘
                                     │
                         ┌───────────▼────────────┐
                         │  Semantic Cache Check    │  (Day 15: embed query,
                         │  (tenant-scoped)         │   check similarity hit)
                         └───────────┬────────────┘
                          cache miss │  cache hit → return cached answer
                         ┌───────────▼────────────┐
                         │  Guardrail: Input        │  (Day 14: injection/
                         │  Check                   │   jailbreak scan)
                         └───────────┬────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              │                      │                       │
   ┌──────────▼─────────┐ ┌──────────▼─────────┐  ┌─────────▼─────────┐
   │  Dense Retrieval     │ │  Sparse Retrieval    │  │  Model Router      │
   │  (Vector DB, sharded │ │  (BM25/Elasticsearch,│  │  (Day 15: picks    │
   │   by tenant_id,      │ │   tenant-filtered)    │  │   model tier for   │
   │   Day 11)             │ │                       │  │   generation)      │
   └──────────┬───────────┘ └──────────┬────────────┘  └─────────┬─────────┘
              │                        │                          │
              └───────────┬────────────┘                          │
                ┌──────────▼───────────┐                          │
                │  Fusion + Re-rank      │                          │
                │  (Day 12: RRF, then    │                          │
                │   cross-encoder)       │                          │
                └──────────┬───────────┘                          │
                           │                                       │
                ┌──────────▼───────────┐                          │
                │  Grounded Generation   │◀─────────────────────────┘
                │  (LLM call w/ context, │
                │   citations required,  │
                │   Day 9-10, 12)         │
                └──────────┬───────────┘
                           │
                ┌──────────▼───────────┐
                │  Output Guardrails     │  (Day 14: groundedness,
                │  (block/allow)         │   PII, schema check)
                └──────────┬───────────┘
                  pass │        │ blocked
        ┌──────────────▼┐   ┌──▼──────────────────┐
        │  Return answer  │   │  Fallback: keyword    │
        │  + citations,    │   │  results / "connect   │
        │  cache the pair  │   │  with a human"         │
        └─────────────────┘   └───────────────────────┘

   ─── Ingestion path (async, separate from query path) ───
   [Ticket/KB/Doc source] → Change Event (Kafka, Day 5) →
     Chunking Service (structural, Day 14) → Embedding Worker →
     Upsert into sharded Vector DB + Sparse Index (tenant-scoped)
```

**Why each Day-9-through-15 concept shows up here:**

| Component | Concept from | Why |
|---|---|---|
| Semantic Cache | Day 15 | Absorbs repetitive support queries before they hit retrieval/generation at all |
| Input guardrail | Day 14 | Catches injection/jailbreak before it can influence retrieval or generation |
| Dense + Sparse retrieval | Days 11–12 | Hybrid search — embeddings for meaning, keyword index for exact terms (error codes, IDs) |
| Sharded vector DB by `tenant_id` | Day 11 (+ Day 4 pattern) | ~2TB of vectors at scale requires sharding; tenant-aligned shard key also enforces isolation at the storage layer, not just in application code |
| Fusion + re-rank | Day 12 | RRF merges dense/sparse rankings; cross-encoder re-rank narrows to the true top candidates before the expensive generation call |
| Model router | Day 15 | Routes to the cheapest model tier capable of the query — simple lookups don't need the most expensive model |
| Grounded generation w/ citations | Days 9–10, 12 | Forces the model to tie claims to retrieved chunks, which the output guardrail can then verify |
| Output guardrails | Day 14 | Groundedness, PII, and schema checks before anything reaches the user |
| Async ingestion via Kafka | Day 5 pattern (applied here) | Ticket/KB updates must not block on a full reindex; incremental embed + upsert keeps freshness targets (1 min / 15 min) achievable |

---

## Step 5 — Deep Dive 1: How do you keep the index fresh without full reindexing?

Reindexing 320M chunks on every ticket update is a non-starter against a
1-minute freshness SLA. Instead: source systems (ticketing DB, KB CMS) emit
change events onto a Kafka topic (Day 5 pattern) on create/update/delete. A
Chunking Service consumes the event, re-chunks only the changed document
(structural chunking, Day 14-adjacent), an Embedding Worker computes new
vectors for just those chunks, and an upsert (not a full rebuild) replaces
the old chunks for that document ID in both the dense and sparse indexes.
Deletes propagate the same way — a tombstone upsert removes stale chunks so
retrieval never surfaces content that's since been deleted or corrected.

## Step 6 — Deep Dive 2: How do you guarantee tenant isolation, not just "usually filter by tenant_id"?

Filtering by `tenant_id` in application code is necessary but not
sufficient — a single missed `WHERE tenant_id = ?` in one code path is a
cross-tenant data leak. Defense in depth here means:

- **Storage-level partitioning**: shard the vector DB and sparse index by
  `tenant_id` so tenant data is *physically* separated, not just logically
  filtered — a bug in a query can't accidentally scan another tenant's shard.
- **Retrieval-time enforcement**: tenant filter is applied inside both the
  dense and sparse search calls themselves (as covered in Day 14's pipeline),
  never as a post-filter on already-merged results.
- **Re-ranker isolation**: if a shared cross-encoder model is used across
  tenants, it must only ever see the already-tenant-filtered candidate pool —
  never trained or fine-tuned in a way that mixes tenant data.
- **Audit logging**: every retrieval call logs the `tenant_id` filter applied,
  so isolation is independently verifiable after the fact, not just assumed
  from code review.

## Step 7 — Deep Dive 3: Why route to different models for chat assistant vs. agent-assist?

Both consumers share the same retrieval infrastructure, but their downstream
policies differ: the customer chat assistant needs stricter output
guardrails (no raw internal notes, conservative tone, mandatory "connect
with a human" fallback) and can often use a cheaper, faster model for
well-templated questions. The internal agent-assist panel serves power users
who want more raw detail, more of the retrieved context surfaced directly,
and can tolerate a marginally higher-cost model for a more thorough answer.
Routing (Day 15) happens *after* retrieval but *before* generation, so the
retrieval investment (embeddings, indexing, hybrid search) is fully shared
while generation cost and guardrail strictness diverge per consumer.

---

## Step 8 — Bottlenecks & Failure Modes (what a strong candidate raises unprompted)

- **Vector DB shard hot-spotting** on a very large enterprise tenant →
  sub-shard the largest tenants by document category or date range, same
  pattern as Day 4's hot-partition mitigation.
- **Re-ranker latency at peak QPS** → cap the candidate pool size fed into
  the cross-encoder (e.g., top 50, not top 500) to bound re-ranking cost;
  this is the same cheap-recall/expensive-precision trade-off from Day 12.
- **LLM provider outage during generation** → circuit breaker + fallback
  model chain (Day 14–15); if all generation options are down, return the
  raw top retrieved chunks as clickable results rather than nothing.
- **Semantic cache poisoning** → if a wrong cached answer gets reused
  because policy changed after caching, enforce a TTL (Day 15) and
  invalidate cache entries tied to documents that were just updated via the
  ingestion event stream.
- **Groundedness false negatives** (guardrail blocks a genuinely correct
  answer) → monitor block rate per tenant/query category; a spike suggests
  the threshold needs tuning, not that the guardrail should be removed.

---

## Interview Q&A (cover the answers, quiz yourself)

**Q1: Why shard the vector DB by `tenant_id` instead of by document type or a hash of the vector itself?**
A: The shard key should align with the dominant access pattern and the
hardest isolation requirement — nearly every query here is scoped to a
single tenant, and isolation between tenants is a security requirement, not
just a performance one. Sharding by `tenant_id` means a query never has to
scatter-gather across shards it doesn't need, and it makes tenant isolation
a property of the storage layer itself rather than something application
code has to get right on every code path.

**Q2: Why do you need both dense and sparse retrieval instead of just embeddings, given embeddings capture "meaning"?**
A: Embeddings are strong at semantic matching but weak at exact-token
precision — error codes, ticket IDs, product SKUs — because the embedding
space compresses text in a way that doesn't preserve exact surface forms.
Sparse/keyword retrieval (BM25) is the complement: strong on exact terms,
blind to paraphrase. Hybrid search with a fusion step (RRF) covers both
failure modes instead of picking one and accepting the other's blind spot.

**Q3: How would you decide the candidate pool size fed into the re-ranker?**
A: It's a latency/cost vs. precision trade-off — a larger pool gives the
re-ranker more chances to surface the truly best chunk, but cross-encoder
re-ranking is expensive per candidate, so pool size directly drives p95
latency at peak QPS. In practice you'd tune this empirically against the
2.5s latency budget: start conservative (e.g., top 50), measure the
re-ranking time contribution at peak load, and adjust from there rather than
guessing a number upfront.

**Q4: What's the actual failure path if the LLM generation call is down but retrieval still works?**
A: Never fabricate an answer from a broken generation path — fall back to
degraded-but-honest behavior: return the top retrieved chunks directly as
linked source documents ("here's what I found, but I can't summarize it
right now") for agent-assist, and for the customer-facing chat, fall back to
"let me connect you with a human agent." This is the same graceful
degradation principle from Day 8's ticket-ingestion SPOF handling, applied
to the generation stage instead of the ingestion stage.

**Q5: How does the semantic cache interact with the freshness requirement — could it serve stale answers after a KB article changes?**
A: Yes, that's a real risk, which is why cache entries need both a TTL (Day
15) and an invalidation hook tied to the ingestion event stream — when a
document is updated, any cache entries whose cited source includes that
document ID should be actively invalidated, not just left to expire on TTL
alone. This is the same tension Day 14's groundedness check exists for:
a fast, cheap path (the cache) can't be allowed to silently outrun
correctness.

**Q6: If you had to cut scope for a 45-minute interview, what would you defer?**
A: Cross-region replication of the vector index, fine-grained per-tenant
model tier contracts, and the exact chunking heuristics for every document
type — mention them as "future work" rather than designing live, to protect
time for the core retrieval-to-generation data flow and the tenant isolation
story, which is what's actually being evaluated.

---

## Self-Evaluation Checklist

- [ ] Asked clarifying questions before designing (scale, freshness, isolation, failure behavior)
- [ ] Gave rough capacity numbers before drawing boxes — especially vector storage size
- [ ] Justified hybrid search with a concrete failure mode (exact-term misses), not just "it's better"
- [ ] Treated tenant isolation as a storage-layer property, not just an application-code filter
- [ ] Named at least one real bottleneck (hot shard, re-ranker latency, cache staleness) unprompted
- [ ] Described a genuine fallback for LLM outage — not "the system just handles it"
- [ ] Could trace every box in the diagram back to a requirement from Step 2
- [ ] Kept the whole walkthrough inside ~40 minutes, leaving time for Q&A

---

## Phase 2 Retrospective (Days 9–16)

| Day | Topic | Where it showed up today |
|---|---|---|
| 9 | LLM Fundamentals | Underpins every generation call in the pipeline |
| 10 | Prompt Engineering | Grounded generation prompt design, citation requirements |
| 11 | Embeddings + Vector DBs | Dense retrieval, sharded vector index sizing |
| 12 | RAG Pipeline Deep Dive | Fusion + re-rank stage, chunking, groundedness |
| 13 | Tool Use + Function Calling | Model router's decision logic mirrors a tool-selection pattern |
| 14 | Guardrails + Reliability | Input/output guardrails, tenant isolation defense-in-depth |
| 15 | LLM Ops: Cost + Latency | Semantic cache, model routing, per-tenant observability |
| 16 | Mock interview (this doc) | Synthesis — all of the above under time pressure |

**Portfolio note**: like Day 8, this document is a strong candidate for a
top-level `RAG-SYSTEM-DESIGN.md` or the repo's `docs/adr/` folder — it
connects directly to the Spring Boot + ADR + Docker Compose direction
planned for turning this repo into a full portfolio piece, and it's concrete
enough to link in a LinkedIn post about the AESP build.

**Phase 2 complete (8/8)** — Phase 3 (Agentic + Autonomous AI, Days 17–22)
picks up directly from here: Day 17 (Agent architectures — ReAct,
Plan-Execute) formalizes the model router and tool-use patterns from Days
13 and 15 into a full agent loop.
