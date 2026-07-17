# Day 15 — LLM Ops: Cost + Latency

**Phase 2: AI Integration + RAG | AESP Component: Semantic Cache**

---

## 1. The Analogy — The Support Floor Learns to Stop Asking the Same Question Twice

By Day 14, our support rep is knowledgeable, well-instructed, tool-equipped, and supervised. But imagine a busy call center where every single agent, for every single call, re-reads the entire policy manual from page one and re-derives the answer from scratch — even when the exact same question ("how do I reset my password?") gets asked five hundred times a day. That's expensive (every agent's time costs money) and slow (every customer waits for the full lookup).

A well-run floor keeps a **shared cheat-sheet** at the front desk: common questions and their vetted answers, updated whenever policy changes, so most calls get answered instantly without waking up a senior rep at all. That's a semantic cache — except instead of matching on the *exact* wording of the question, it recognizes that "how do I reset my password" and "I forgot my password, help" are the same question, because it compares *meaning*, not text.

The floor manager also doesn't send every call to the most senior (most expensive) rep by default — routine questions go to a junior rep or the cheat-sheet; only genuinely novel or high-stakes questions escalate to the expert. That's model routing: matching request complexity to the cheapest model capable of handling it.

---

## 2. The Concept

### 2.1 Where LLM cost and latency actually come from

- **Cost** scales primarily with **tokens** — input tokens (your prompt + retrieved context + conversation history) and output tokens (the generated response). Long RAG contexts (Day 12) and long conversation histories are the biggest silent cost drivers.
- **Latency** scales with output length (tokens are generated sequentially) and model size — larger, more capable models are typically slower per token.
- These two pressures — cost and latency — usually point toward the same fix: **use the smallest, cheapest model and the shortest prompt that reliably gets the job done**, and only pay for more when the task actually needs it.

### 2.2 Semantic caching

An exact-match cache (hash the input, check for a hit) barely helps LLM traffic — real user queries are rarely worded identically twice. A **semantic cache** instead:

1. Embeds the incoming query.
2. Searches a vector store of *previously answered* queries for one above a similarity threshold.
3. On a hit, returns the cached response (after a fast validity check — was this answer time-sensitive? Has the underlying data changed?) instead of calling the LLM at all.
4. On a miss, calls the LLM normally, then stores the new query+response pair for future hits.

The similarity threshold is the key tuning knob: too loose, and unrelated questions get the wrong cached answer (a correctness risk, not just a UX one); too tight, and the cache rarely fires.

### 2.3 Model routing / cascading

Not every request needs your most capable (most expensive) model. A **router** — often a small, fast classifier or even a rule-based check — decides which model handles a given request:

- Simple, well-templated queries ("what are your business hours") → smallest/cheapest model, or the semantic cache directly.
- Standard support queries with RAG context → mid-tier model.
- Complex, ambiguous, or high-stakes queries (large refunds, legal/compliance language, angry escalations) → your most capable model, possibly with Day 14's stricter guardrails attached.

### 2.4 Other cost/latency levers

- **Prompt caching** (provider-level): if your system prompt and RAG context prefix are identical across many requests, some providers let you cache that prefix server-side so you're not re-billed/re-processed for it every call.
- **Streaming responses**: doesn't reduce total cost or total latency, but dramatically improves *perceived* latency — the user sees tokens arriving immediately instead of waiting for the full response.
- **Batching**: for non-real-time workloads (e.g., nightly ticket summarization), batch APIs trade latency for significantly lower cost.
- **Context trimming**: truncating conversation history and retrieved chunks to only what's relevant (Day 11–12's chunking and retrieval quality directly reduces token spend here) rather than always sending the maximum context window.

### 2.5 Observability — you can't control what you don't measure

Cost/latency optimization requires per-request visibility: token counts (input/output), model used, cache hit/miss, latency breakdown (retrieval time vs. generation time vs. guardrail time). Without this, "the AI bill is high" has no actionable next step.

---

## 3. Code

### 3.1 Java (Spring Boot) — Semantic Cache + Model Router

```java
// SemanticCacheService.java
package com.aesp.llmops;

import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class SemanticCacheService {

    private final EmbeddingClient embeddingClient;
    private final CacheVectorStore cacheStore; // stores (embedding, response, cachedAt, tenantId)

    private static final double SIMILARITY_THRESHOLD = 0.93; // tuned tight to avoid wrong-answer cache hits
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    public SemanticCacheService(EmbeddingClient embeddingClient, CacheVectorStore cacheStore) {
        this.embeddingClient = embeddingClient;
        this.cacheStore = cacheStore;
    }

    public Optional<CachedResponse> lookup(String query, String tenantId) {
        float[] queryEmbedding = embeddingClient.embed(query);
        Optional<CacheEntry> hit = cacheStore.findNearest(queryEmbedding, tenantId, SIMILARITY_THRESHOLD);

        return hit
                .filter(entry -> !isExpired(entry))
                .map(entry -> new CachedResponse(entry.response(), entry.similarityScore()));
    }

    public void store(String query, String response, String tenantId) {
        float[] queryEmbedding = embeddingClient.embed(query);
        cacheStore.save(new CacheEntry(queryEmbedding, response, Instant.now(), tenantId));
    }

    private boolean isExpired(CacheEntry entry) {
        return Instant.now().isAfter(entry.cachedAt().plus(CACHE_TTL));
    }

    public record CachedResponse(String response, double similarityScore) {}
    public record CacheEntry(float[] embedding, String response, Instant cachedAt, String tenantId) {
        double similarityScore() { return 0.0; } // populated by cacheStore.findNearest
    }
}
```

```java
// ModelRouter.java — routes requests to the cheapest capable model
package com.aesp.llmops;

import org.springframework.stereotype.Service;

@Service
public class ModelRouter {

    public enum ModelTier { SMALL_FAST, STANDARD, LARGE_CAPABLE }

    public ModelTier route(RoutingContext ctx) {
        if (ctx.isHighRiskAction() || ctx.requiresComplexReasoning()) {
            return ModelTier.LARGE_CAPABLE;
        }
        if (ctx.hasRagContext() || ctx.conversationTurnCount() > 3) {
            return ModelTier.STANDARD;
        }
        return ModelTier.SMALL_FAST; // simple, templated, low-stakes queries
    }

    public record RoutingContext(
            boolean isHighRiskAction,
            boolean requiresComplexReasoning,
            boolean hasRagContext,
            int conversationTurnCount) {}
}
```

```java
// LlmOpsMetrics.java — per-request observability
package com.aesp.llmops;

import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class LlmOpsMetrics {

    private final MeterRegistry registry;

    public LlmOpsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRequest(String tenantId, String modelTier, boolean cacheHit,
                               int inputTokens, int outputTokens, Duration latency) {
        registry.counter("llm.requests", "tenant", tenantId, "tier", modelTier,
                "cache_hit", String.valueOf(cacheHit)).increment();
        registry.summary("llm.tokens.input", "tenant", tenantId).record(inputTokens);
        registry.summary("llm.tokens.output", "tenant", tenantId).record(outputTokens);
        registry.timer("llm.latency", "tenant", tenantId, "tier", modelTier)
                .record(latency);
    }
}
```

### 3.2 Node.js — Same Pipeline

```javascript
// semanticCacheService.js
const { embedQuery } = require('./embeddingClient');
const { findNearestCached, saveCacheEntry } = require('./cacheVectorStore');

const SIMILARITY_THRESHOLD = 0.93;
const CACHE_TTL_MS = 6 * 60 * 60 * 1000;

async function lookup(query, tenantId) {
  const queryEmbedding = await embedQuery(query);
  const hit = await findNearestCached(queryEmbedding, tenantId, SIMILARITY_THRESHOLD);

  if (!hit) return null;
  if (Date.now() - hit.cachedAt > CACHE_TTL_MS) return null;

  return { response: hit.response, similarityScore: hit.similarityScore };
}

async function store(query, response, tenantId) {
  const queryEmbedding = await embedQuery(query);
  await saveCacheEntry({ embedding: queryEmbedding, response, cachedAt: Date.now(), tenantId });
}

module.exports = { lookup, store };
```

```javascript
// modelRouter.js — routes requests to the cheapest capable model
const TIERS = { SMALL_FAST: 'small_fast', STANDARD: 'standard', LARGE_CAPABLE: 'large_capable' };

function route(ctx) {
  if (ctx.isHighRiskAction || ctx.requiresComplexReasoning) {
    return TIERS.LARGE_CAPABLE;
  }
  if (ctx.hasRagContext || ctx.conversationTurnCount > 3) {
    return TIERS.STANDARD;
  }
  return TIERS.SMALL_FAST;
}

module.exports = { route, TIERS };
```

```javascript
// llmOpsMetrics.js — per-request observability
const client = require('prom-client');

const requestCounter = new client.Counter({
  name: 'llm_requests_total',
  help: 'Total LLM requests',
  labelNames: ['tenant', 'tier', 'cache_hit'],
});
const tokenHistogram = new client.Histogram({
  name: 'llm_tokens',
  help: 'Token counts per request',
  labelNames: ['tenant', 'direction'],
});
const latencyHistogram = new client.Histogram({
  name: 'llm_latency_seconds',
  help: 'LLM request latency',
  labelNames: ['tenant', 'tier'],
});

function recordRequest(tenantId, tier, cacheHit, inputTokens, outputTokens, latencySeconds) {
  requestCounter.inc({ tenant: tenantId, tier, cache_hit: String(cacheHit) });
  tokenHistogram.observe({ tenant: tenantId, direction: 'input' }, inputTokens);
  tokenHistogram.observe({ tenant: tenantId, direction: 'output' }, outputTokens);
  latencyHistogram.observe({ tenant: tenantId, tier }, latencySeconds);
}

module.exports = { recordRequest };
```

---

## 4. AESP Context

By Day 15, AESP has a real cost and latency profile to manage — every prior day's capability (RAG retrieval, tool use, guardrail checks) adds tokens and round-trips, and at enterprise scale that compounds fast:

- **Why semantic cache matters for a support platform specifically:** support traffic is extremely repetitive — "how do I reset my password," "where's my order," "how do I cancel" — the same handful of intents phrased a hundred different ways across thousands of tickets. This is close to the ideal workload for semantic caching, and a well-tuned cache can absorb a large fraction of traffic before it ever reaches the LLM or the RAG pipeline.
- **Why the similarity threshold is a correctness decision, not just a cost one:** a cache hit that's actually the wrong answer (e.g., matching "cancel my order" to a cached "cancel my subscription" answer) is worse than a cache miss — it's Day 14's groundedness problem wearing a different hat. The threshold has to be tuned conservatively, and cache entries should carry a TTL since policy and product details change.
- **Why model routing matters in a multi-tenant system:** different tenants have different contracts — some may be paying for guaranteed access to your most capable model tier, others on a lower-cost plan should be routed to cheaper models by default. The router needs tenant context, not just query complexity, to route correctly.
- **Why per-tenant observability matters for a B2B platform:** AESP bills tenants based on usage, and support/reliability teams need to know if one tenant's traffic pattern (e.g., unusually long conversation histories) is driving disproportionate cost — this is exactly what the per-tenant metrics in `LlmOpsMetrics`/`llmOpsMetrics.js` are for.
- **Interaction with Day 14's guardrails:** a groundedness or PII check adds its own latency and (if it's an LLM-as-judge check) its own token cost — cost/latency optimization and guardrail rigor are in constant tension, and production systems have to make that trade-off explicit rather than accidentally, e.g., using a cheap NLI-based entailment check by default and reserving expensive LLM-as-judge checks for high-risk actions only.

---

## 5. Interview Q&A

**Q1: Why doesn't an exact-match cache work well for LLM traffic, and what does semantic caching do differently?**
A: Exact-match caching requires the incoming text to be byte-identical to a previous cache key, but real user queries are rarely worded the same way twice even when the underlying intent is identical — "how do I reset my password" and "forgot my password, help" would never hit an exact-match cache despite needing the same answer. Semantic caching compares the *embedding* of the incoming query against previously cached queries and returns a hit above a similarity threshold, so it's matching on meaning rather than surface text.

**Q2: What's the risk of setting a semantic cache's similarity threshold too loose, and how would you catch it?**
A: Too loose a threshold means genuinely different questions get matched to the same cached answer — this isn't just a UX annoyance, it's a correctness failure similar to hallucination, since the user receives a wrong answer with full apparent confidence. You'd catch it by monitoring cache-hit outcomes against user feedback/escalation rates, and by keeping the threshold conservative by default, tuning it looser only where you have evidence it's safe for that query category.

**Q3: How does model routing reduce cost without just degrading answer quality across the board?**
A: Routing matches request complexity to the cheapest model capable of handling it, rather than sending every request to the most capable model by default — a simple templated query doesn't need the same model as an ambiguous, high-stakes escalation. The key is that routing decisions are based on genuine complexity/risk signals (conversation depth, presence of RAG context, high-risk action flags), not applied uniformly, so quality is preserved specifically where it matters and cost is cut specifically where it doesn't.

**Q4: What's the difference between reducing latency and reducing *perceived* latency, and where does streaming fit?**
A: Streaming doesn't reduce total generation time or total cost — the same number of tokens still has to be generated — but it changes when the user starts seeing output, from "wait for the entire response" to "see the first token almost immediately." For a chat-style interface, perceived latency is often what actually matters for user experience, even when true end-to-end latency is unchanged.

**Q5: Why is token count from retrieved RAG context often the biggest hidden cost driver, and how would you reduce it?**
A: Every retrieved chunk added to the prompt is billed as input tokens on every single call, and it's easy to over-retrieve "just to be safe" — pulling more chunks than the answer actually needs. Reducing it means tightening retrieval (Day 12–13's better chunking and re-ranking so fewer, more relevant chunks are needed), trimming conversation history to what's actually relevant, and where supported, using provider-level prompt caching for context that's identical across many requests.

**Q6: How would you design observability so that "the AI bill is high" turns into an actionable next step?**
A: You need per-request breakdown, not just an aggregate bill: token counts split by input/output, which model tier handled the request, cache hit or miss, and a latency breakdown by stage (retrieval, generation, guardrail checks). Tagged per-tenant, this turns a vague cost concern into a specific finding — e.g., "Tenant X's conversations average 40 turns before resolution, driving 3x the token cost of other tenants" — which is something you can actually act on, versus an opaque total.

---

*Next: Day 16 — Phase 2 Mock Interview: Design a RAG Search System*
