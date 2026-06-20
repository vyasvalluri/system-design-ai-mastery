# Interview Preparation — System Design + AI Systems

> All questions are answered through the lens of AESP. After 30 days, you'll have model answers for every question below.

---

## System Design Questions (Senior SDE / Staff Engineer)

### Foundational
- [ ] Design a URL shortener (TinyURL)
- [ ] Design a rate limiter
- [ ] Design a key-value store
- [ ] Design a web crawler
- [ ] Design a notification system

### Intermediate
- [ ] Design Twitter / X feed
- [ ] Design WhatsApp / messaging system
- [ ] Design YouTube / video streaming
- [ ] Design a ride-sharing system (Uber)
- [ ] Design a hotel booking system

### Advanced
- [ ] Design a distributed cache (Redis)
- [ ] Design a search engine (Elasticsearch)
- [ ] Design a payment system
- [ ] Design a multi-tenant SaaS platform ← AESP is this
- [ ] Design a real-time analytics dashboard

---

## AI Systems Questions (Emerging — strong differentiator)

### RAG & Knowledge Systems
- [ ] Design a document QA system using RAG
- [ ] How would you improve RAG retrieval accuracy?
- [ ] How do you handle multi-lingual RAG?
- [ ] Design a semantic search engine

### Agentic AI
- [ ] Design an AI customer support agent ← AESP is this
- [ ] Design GitHub Copilot / AI code assistant
- [ ] Design an autonomous data analyst agent
- [ ] How do you prevent AI agent infinite loops?
- [ ] How do you implement human-in-the-loop in agentic systems?

### LLM Infrastructure
- [ ] How do you reduce LLM latency in production?
- [ ] How do you handle LLM cost at scale?
- [ ] How do you evaluate LLM output quality?
- [ ] Design an LLM gateway / proxy

---

## Framework for Answering Any System Design Question

Use this structure in every interview:

```
1. CLARIFY (2–3 min)
   - Functional requirements: what does the system do?
   - Non-functional: scale, latency, availability, consistency?
   - Out of scope: what are we NOT building?

2. ESTIMATE (2 min)
   - DAU / MAU
   - Read:write ratio
   - QPS (queries per second)
   - Storage needs (back-of-envelope)

3. HIGH LEVEL DESIGN (5–8 min)
   - Draw the major components: clients, API layer, services, data stores
   - Show the happy path end-to-end

4. DEEP DIVE (10–15 min)
   - Pick 2–3 interesting components and go deep
   - Show you understand the tradeoffs

5. HANDLE BOTTLENECKS (5 min)
   - What breaks first at scale?
   - How do you fix it?

6. WRAP UP (2 min)
   - Summarize design decisions
   - Mention what you'd improve with more time
```

---

## Back-of-Envelope Estimation Cheat Sheet

```
Powers of 10 to memorize:
  1 million  = 10^6
  1 billion  = 10^9
  1 trillion = 10^12

Time:
  1 day    = 86,400 seconds ≈ 10^5 seconds
  1 month  = 2.5 million seconds
  1 year   = 31.5 million seconds

Latency (approximate):
  L1 cache read      = 1 ns
  L2 cache read      = 4 ns
  RAM read           = 100 ns
  SSD read           = 100 µs
  Network (same DC)  = 1 ms
  HDD read           = 10 ms
  Network (cross DC) = 100 ms

Throughput rules of thumb:
  1 server (8 core)  = ~1,000–5,000 RPS for API
  PostgreSQL (index) = ~10,000 reads/sec, ~2,000 writes/sec
  Redis              = ~100,000 ops/sec
  Kafka              = ~1,000,000 msgs/sec per broker

Storage:
  1 char  = 1 byte
  1 tweet = ~280 bytes
  1 photo = ~1 MB
  1 video (1 min HD) = ~100 MB
```

---

## AESP-Specific Interview Answers

### "Design an AI-powered customer support system"

**Clarify:**
- Volume: 10M tickets/day, 500K concurrent users
- AI resolution target: 70%+ auto-resolved
- Latency: < 200ms for ticket creation, AI response async

**Key components to mention:**
1. Kafka event bus — decouple ingestion from AI processing
2. Multi-agent pipeline — Classifier → RAG → Resolver/Escalation
3. RAG with Pinecone — knowledge base retrieval
4. Confidence threshold — 85% for auto-resolution
5. Human escalation path — with AI-generated context summary
6. Feedback loop — outcomes re-train embeddings

**Differentiator answers:**
- "I'd use CQRS — write path for ticket ops, read replicas for analytics dashboards"
- "Sharding by tenant_id prevents noisy-neighbor problems in multi-tenant setup"
- "Semantic caching in Redis — if embedding similarity > 0.95, serve cached LLM response"

---

*Updated as each day's session is completed.*
