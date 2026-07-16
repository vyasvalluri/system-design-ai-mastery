# Day 12 — RAG Pipeline Deep Dive

**Phase 2: AI Integration + RAG | AESP Component: RAG Agent**

---

## 1. The Analogy — An Open-Book Exam, Done Right

Day 11 built the librarian who can find the right shelf. Today is about what happens *after* she hands you the books — how you actually pass the open-book exam.

A student who's allowed to bring the whole library into the exam room but who dumps every book on the desk unread, skims randomly, and writes an answer from vague memory will do worse than one who has *none* of the books. The books only help if you: pick the *right few* books (not all of them), read the *right pages* in each, and then write an answer that's clearly grounded in what those pages actually say — not what you vaguely recall from your own memory.

RAG (Retrieval-Augmented Generation) is that disciplined open-book process, automated: retrieve the right passages, assemble them into a clean "exam desk" (the context window), and instruct the model to answer strictly from what's in front of it.

---

## 2. The Concept

Day 11 covered the "R" — how retrieval finds relevant chunks. Today covers the full pipeline end-to-end, and the failure modes that separate a demo RAG system from a production one.

### The five-stage pipeline

| Stage | What happens | Where it breaks in production |
|---|---|---|
| **1. Query understanding** | Raw user question → possibly rewritten/expanded | Vague or multi-part questions retrieve poorly |
| **2. Retrieval** | Embed query, ANN search, return top-K chunks | Wrong K, weak similarity threshold, stale index |
| **3. Re-ranking** | Re-score the top-K with a more precise (often cross-encoder) model | Skipping this step lets noisy chunks through |
| **4. Context assembly** | Order chunks, dedupe, fit token budget, add citations/metadata | Context window overflow, lost-in-the-middle |
| **5. Generation** | LLM answers, grounded in assembled context | Hallucination when context is thin or ambiguous |

### Retrieval quality tricks

- **Query rewriting/expansion**: rewrite a vague query ("it's broken") into a fuller one using conversation history before embedding it, since a bare fragment embeds poorly.
- **Hybrid search**: combine dense vector search (semantic) with sparse keyword search (BM25) — vectors miss exact product names, SKUs, or error codes that keyword search nails. Merge results with reciprocal rank fusion.
- **Re-ranking**: a first-pass ANN search over-fetches (e.g., top 50), then a cross-encoder re-ranker — which scores the *pair* (query, chunk) jointly rather than comparing pre-computed vectors — re-sorts to the true top-K (e.g., 5). Slower per-pair but far more accurate, so it's only run on the shortlist.
- **Metadata filtering**: filter by recency, doc type, or tenant *before* the vector search where possible, not after — narrowing the search space improves both speed and precision.

### Context assembly pitfalls

- **Lost-in-the-middle**: LLMs attend more reliably to content at the start and end of a long context than buried in the middle — so put the highest-relevance chunk first (and/or last), not just in retrieval-score order.
- **Token budget**: chunks + system prompt + conversation history + generation headroom must all fit the model's context window; truncate the least relevant chunks first, never the system prompt.
- **Deduplication**: overlapping chunks from adjacent sections can retrieve near-duplicate text — dedupe before sending, or the model wastes attention re-reading the same fact.

### Grounding and hallucination control

- Instruct the model explicitly to answer *only* from the provided context, and to say "I don't know" rather than fill gaps from parametric memory.
- Ask the model to cite which chunk supported each claim — this both improves faithfulness (models trained to point to a source hallucinate less) and gives you an auditable trail.
- Evaluate with **faithfulness** (is the answer actually supported by the retrieved context?) as a separate metric from **relevance** (did retrieval find the right chunks?) — a system can retrieve perfectly and still hallucinate on generation, or retrieve badly and still sound confident.

---

## 3. Code

### Java (Spring Boot) — orchestrating the retrieval + re-rank + assembly stages

```java
// RagPipelineService.java
@Service
public class RagPipelineService {

    private final EmbeddingService embeddingService; // from Day 11
    private final VectorStoreClient vectorStore;
    private final ReRankerClient reRanker;
    private static final int RETRIEVE_TOP_K = 50;
    private static final int FINAL_TOP_K = 5;
    private static final double MIN_SIMILARITY = 0.70;
    private static final double MIN_RERANK_SCORE = 0.50;

    public RagPipelineService(EmbeddingService embeddingService,
                               VectorStoreClient vectorStore,
                               ReRankerClient reRanker) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.reRanker = reRanker;
    }

    public RagContext buildContext(String query, String tenantId, List<String> history) {
        String expandedQuery = expandWithHistory(query, history);

        float[] queryVector = embeddingService.embed(expandedQuery);

        // Stage 2: over-fetch from the vector store
        List<Chunk> candidates = vectorStore.search(queryVector, tenantId, RETRIEVE_TOP_K)
                .stream()
                .filter(c -> c.similarity() >= MIN_SIMILARITY)
                .toList();

        if (candidates.isEmpty()) {
            return RagContext.empty();
        }

        // Stage 3: cross-encoder re-rank on the shortlist only
        List<Chunk> reRanked = reRanker.rerank(expandedQuery, candidates).stream()
                .filter(c -> c.rerankScore() >= MIN_RERANK_SCORE)
                .sorted(Comparator.comparingDouble(Chunk::rerankScore).reversed())
                .limit(FINAL_TOP_K)
                .toList();

        // Stage 4: dedupe + assemble, highest-relevance chunk first and last
        List<Chunk> deduped = dedupeOverlapping(reRanked);
        List<Chunk> ordered = reorderForAttention(deduped);

        return new RagContext(ordered, expandedQuery);
    }

    private List<Chunk> reorderForAttention(List<Chunk> chunks) {
        if (chunks.size() <= 2) return chunks;
        List<Chunk> reordered = new ArrayList<>(chunks.subList(1, chunks.size()));
        reordered.add(0, chunks.get(0));   // best chunk first
        reordered.add(chunks.get(0));      // repeat best chunk last (cheap, small token cost)
        return reordered;
    }

    private List<Chunk> dedupeOverlapping(List<Chunk> chunks) {
        // Simplified: drop chunks whose content is near-identical to one already kept
        List<Chunk> result = new ArrayList<>();
        for (Chunk c : chunks) {
            boolean duplicate = result.stream()
                    .anyMatch(kept -> jaccardSimilarity(kept.content(), c.content()) > 0.85);
            if (!duplicate) result.add(c);
        }
        return result;
    }

    private double jaccardSimilarity(String a, String b) {
        Set<String> setA = new HashSet<>(List.of(a.split("\\s+")));
        Set<String> setB = new HashSet<>(List.of(b.split("\\s+")));
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private String expandWithHistory(String query, List<String> history) {
        if (history.isEmpty()) return query;
        return String.join(" ", history.subList(Math.max(0, history.size() - 2), history.size()))
                + " " + query;
    }
}
```

### Node.js — hybrid search (dense + sparse) with reciprocal rank fusion, then generation

```javascript
// ragPipeline.js
const { searchKnowledgeBase } = require('./vectorSearch'); // dense, from Day 11
const { bm25Search } = require('./keywordSearch');          // sparse, existing service
const Anthropic = require('@anthropic-ai/sdk');
const client = new Anthropic();

function reciprocalRankFusion(denseResults, sparseResults, k = 60) {
  const scores = new Map();

  const addRanked = (results) => {
    results.forEach((r, rank) => {
      const prev = scores.get(r.id) || { chunk: r, score: 0 };
      prev.score += 1 / (k + rank + 1);
      scores.set(r.id, prev);
    });
  };

  addRanked(denseResults);
  addRanked(sparseResults);

  return Array.from(scores.values())
    .sort((a, b) => b.score - a.score)
    .map(s => s.chunk);
}

async function answerWithRag(query, tenantId) {
  const [dense, sparse] = await Promise.all([
    searchKnowledgeBase(query, tenantId, 20),
    bm25Search(query, tenantId, 20),
  ]);

  const fused = reciprocalRankFusion(dense, sparse).slice(0, 5);

  if (fused.length === 0) {
    return { answer: "I don't have enough information to answer that.", sources: [] };
  }

  const contextBlock = fused
    .map((c, i) => `[Source ${i + 1}: ${c.source}]\n${c.content}`)
    .join('\n\n');

  const response = await client.messages.create({
    model: 'claude-sonnet-4-6',
    max_tokens: 500,
    temperature: 0,
    system: `You are AESP's RAG Agent. Answer ONLY using the provided sources below.
If the sources don't contain the answer, say you don't know — never guess.
Cite the source number for each claim, like [Source 2].`,
    messages: [
      { role: 'user', content: `${contextBlock}\n\nQuestion: ${query}` }
    ],
  });

  return {
    answer: response.content[0].text,
    sources: fused.map(c => ({ id: c.id, source: c.source })),
  };
}

module.exports = { answerWithRag };
```

---

## 4. AESP Context

The RAG Agent is one of the specialized agents the Supervisor Agent routes to (alongside Classifier and Resolver), and its pipeline design directly determines whether AESP's answers are trustworthy at scale:

- **Hybrid search is mandatory, not optional**, because support tickets are full of exact identifiers — order numbers, error codes, plan names — that dense embeddings alone under-match. AESP runs dense + BM25 in parallel and fuses with RRF rather than picking one strategy.
- **Re-ranking is the single biggest quality lever** the team found after launch: over-fetching top-50 from the vector store and re-ranking down to top-5 with a cross-encoder cut "irrelevant chunk in final answer" incidents dramatically, at the cost of a small added latency per query — worth it against the alternative of a wrong support reply going to a paying customer.
- **Faithfulness evaluation runs as a CI gate**: every change to the RAG pipeline (chunking strategy, re-ranker model, prompt wording) runs against a labeled eval set before deploy, scoring both retrieval relevance and generation faithfulness separately, so a regression in either dimension blocks the release — same discipline as the prompt regression tests from Day 10.
- **"I don't know" is a first-class outcome**, not a failure — the Resolver Agent is explicitly instructed to escalate to a human agent (the Escalation Agent, Day 20) rather than let the RAG Agent guess when retrieval confidence is low, since a wrong confident answer damages trust more than a visible escalation.
- **Context assembly respects AESP's multi-tenant token budget**: because each tenant can have different subscription tiers with different max-context allowances, the assembly stage truncates by relevance score first, always preserving the system prompt and the top re-ranked chunk.

---

## 5. Interview Q&A

**Q1: Walk through the stages of a production RAG pipeline, beyond just "embed and retrieve."**
A: Query understanding/rewriting, retrieval (often over-fetching a wider candidate set), re-ranking that candidate set with a more precise model, context assembly (ordering, deduping, fitting the token budget), and finally generation with explicit grounding instructions. Treating retrieval as the whole pipeline is the most common reason demo RAG systems underperform in production.

**Q2: Why add a re-ranking step if the vector search already returns results sorted by similarity?**
A: Vector similarity from a bi-encoder is fast but approximate — it compares independently pre-computed embeddings. A cross-encoder re-ranker scores the query and chunk jointly, capturing interactions a bi-encoder misses, which meaningfully improves precision on the shortlist. It's only run on the top candidates (not the whole corpus) because it's too slow to use as the primary search mechanism.

**Q3: What is hybrid search and why would you use it over pure vector search?**
A: Combining dense vector search (semantic similarity) with sparse keyword search like BM25 (exact term matching), typically merged via reciprocal rank fusion. Vector search alone can miss exact identifiers — SKUs, error codes, product names — that a user's query names literally but that don't carry strong semantic signal on their own.

**Q4: What's "lost-in-the-middle" and how does it affect how you assemble RAG context?**
A: LLMs attend more reliably to information near the start and end of a long context window than to content buried in the middle. In practice this means ordering retrieved chunks by relevance and placing the most important one first (and sometimes repeating it at the end), rather than just concatenating chunks in raw retrieval order.

**Q5: How do you evaluate a RAG system, and why is that different from evaluating the LLM alone?**
A: Evaluate retrieval and generation as separate concerns: retrieval relevance (did we find the right chunks?) and generation faithfulness (is the answer actually grounded in what was retrieved, not hallucinated?). A system can fail at either stage independently — perfect retrieval with a hallucinated answer, or confident-sounding generation built on the wrong chunks — so a single end-to-end "is the answer good" metric hides which stage needs fixing.

---
