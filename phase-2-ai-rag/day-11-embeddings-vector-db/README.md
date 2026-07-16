# Day 11 — Embeddings + Vector Databases

**Phase 2: AI Integration + RAG | AESP Component: Pinecone / pgvector**

---

## 1. The Analogy — A Librarian Who Understands Meaning, Not Just Keywords

Imagine a traditional library catalog: it can only find a book if you type the exact title or an exact keyword from it. Ask for "a book about a whale hunting a sea captain" and it comes up empty unless you happen to type "Moby Dick."

Now imagine a librarian who has *read* every book and organized the shelves by **meaning** — so books about obsession, revenge, and the sea sit near each other even if they never share a single word. Ask that librarian your vague question, and she walks straight to the right shelf.

Embeddings are how we give a computer that librarian's sense of meaning: every piece of text is converted into a vector (a list of numbers) such that texts with similar *meaning* end up as nearby points in that space. A vector database is the reorganized library itself — a structure built to answer "what's near this point?" at massive scale, in milliseconds, across millions of books.

---

## 2. The Concept

### What an embedding actually is

An embedding is a fixed-length array of floating-point numbers (e.g., 1536 dimensions for `text-embedding-3-large`, or a few hundred for smaller open models) produced by a neural network trained so that semantically similar inputs produce vectors that are close together under some distance metric.

- **Cosine similarity** — measures the angle between two vectors; most common for text, ignores magnitude.
- **Euclidean (L2) distance** — straight-line distance; sensitive to magnitude.
- **Dot product** — fast, common when vectors are pre-normalized.

Two ticket texts like *"I can't log into my account"* and *"password reset isn't working"* will land close together in embedding space even though they share almost no words — that's the entire value proposition over keyword search (BM25/TF-IDF).

### Why you can't just use a regular database

A standard B-tree index answers "give me rows where `id = 5`." It cannot efficiently answer "give me the 10 rows whose 1536-dimensional vector is closest to this one" — that's a nearest-neighbor search problem, and doing it by brute-force comparison against millions of rows is too slow for production latency budgets.

### Approximate Nearest Neighbor (ANN) — the core trick

Exact nearest-neighbor search is O(n) per query. Vector databases use **approximate** algorithms that trade a small amount of recall for massive speed:

| Algorithm | Idea | Used by |
|---|---|---|
| **HNSW** (Hierarchical Navigable Small World) | Multi-layer graph of vectors; search hops through layers, coarse to fine | Pinecone, pgvector, Weaviate, Qdrant |
| **IVF** (Inverted File Index) | Cluster vectors into buckets (via k-means); only search the nearest buckets | FAISS, Milvus |
| **PQ** (Product Quantization) | Compress vectors into smaller codes to save memory/speed, at some accuracy cost | Often combined with IVF (IVF-PQ) |

HNSW is the dominant choice today because it gives the best recall/latency tradeoff for most workloads without needing to retrain clusters as data grows.

### Choosing a vector store

| Option | Type | When to use |
|---|---|---|
| **Pinecone** | Managed, vector-native | No ops overhead, scales to billions of vectors, built-in metadata filtering |
| **pgvector** | Postgres extension | Already on Postgres, want vectors + relational data in one transaction, moderate scale |
| **Weaviate / Qdrant / Milvus** | Self-hosted vector-native | Full control, on-prem requirements, very large scale with tuning |

### Chunking — the decision that matters more than the model

Embeddings are computed per chunk, not per document, because a single embedding for a 50-page manual is too diluted to match a specific question. Common strategies:

- **Fixed-size with overlap** — e.g., 500 tokens with 50-token overlap; simple, works well as a baseline.
- **Semantic chunking** — split at natural boundaries (headings, paragraphs) so each chunk is a coherent idea.
- **Recursive splitting** — try paragraph boundaries first, fall back to sentence, then to fixed-size, to avoid ever cutting mid-thought.

Chunk size is a tradeoff: too small loses context, too large dilutes the embedding and wastes retrieval budget on irrelevant text pulled in alongside the relevant sentence.

---

## 3. Code

### Java (Spring Boot) — generating embeddings and upserting to Pinecone

```java
// EmbeddingService.java
@Service
public class EmbeddingService {

    private final WebClient openAiClient;
    private final WebClient pineconeClient;

    public EmbeddingService(WebClient.Builder builder,
                             @Value("${openai.api-key}") String openAiKey,
                             @Value("${pinecone.api-key}") String pineconeKey,
                             @Value("${pinecone.index-url}") String pineconeUrl) {
        this.openAiClient = builder
                .baseUrl("https://api.openai.com/v1")
                .defaultHeader("Authorization", "Bearer " + openAiKey)
                .build();
        this.pineconeClient = builder
                .baseUrl(pineconeUrl)
                .defaultHeader("Api-Key", pineconeKey)
                .build();
    }

    public float[] embed(String text) {
        EmbeddingRequest request = new EmbeddingRequest("text-embedding-3-large", text);

        EmbeddingResponse response = openAiClient.post()
                .uri("/embeddings")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();

        return response.data().get(0).embedding();
    }

    public void upsertChunk(String chunkId, String text, Map<String, Object> metadata) {
        float[] vector = embed(text);

        PineconeUpsertRequest upsert = new PineconeUpsertRequest(
                List.of(new PineconeVector(chunkId, vector, metadata)),
                "aesp-knowledge-base" // namespace, e.g. per tenant
        );

        pineconeClient.post()
                .uri("/vectors/upsert")
                .bodyValue(upsert)
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}

record EmbeddingRequest(String model, String input) {}
record EmbeddingResponse(List<EmbeddingData> data) {}
record EmbeddingData(float[] embedding) {}
record PineconeVector(String id, float[] values, Map<String, Object> metadata) {}
record PineconeUpsertRequest(List<PineconeVector> vectors, String namespace) {}
```

### Node.js — querying pgvector directly with metadata filtering

```javascript
// vectorSearch.js
const { Pool } = require('pg');
const OpenAI = require('openai');

const pool = new Pool({ connectionString: process.env.DATABASE_URL });
const openai = new OpenAI();

async function embedQuery(text) {
  const res = await openai.embeddings.create({
    model: 'text-embedding-3-large',
    input: text,
  });
  return res.data[0].embedding;
}

// Assumes: CREATE TABLE kb_chunks (
//   id UUID PRIMARY KEY, tenant_id UUID, content TEXT,
//   embedding VECTOR(1536), source VARCHAR
// );
// CREATE INDEX ON kb_chunks USING hnsw (embedding vector_cosine_ops);

async function searchKnowledgeBase(queryText, tenantId, topK = 5) {
  const queryVector = await embedQuery(queryText);
  const vectorLiteral = `[${queryVector.join(',')}]`;

  const { rows } = await pool.query(
    `SELECT id, content, source,
            1 - (embedding <=> $1::vector) AS similarity
     FROM kb_chunks
     WHERE tenant_id = $2
     ORDER BY embedding <=> $1::vector
     LIMIT $3`,
    [vectorLiteral, tenantId, topK]
  );

  // Filter out weak matches — don't let irrelevant chunks reach the LLM
  return rows.filter(r => r.similarity > 0.75);
}

module.exports = { searchKnowledgeBase };
```

---

## 4. AESP Context

In AESP, embeddings and vector search power the **RAG Agent**, which grounds every support reply in real product documentation instead of the model's parametric memory:

- **Ingestion pipeline**: whenever a doc is added/updated (help center article, release note, internal runbook), it's chunked, embedded, and upserted into Pinecone — keyed with `tenant_id` metadata so one customer's private docs never leak into another tenant's retrieval results.
- **Multi-tenancy via namespaces**: each tenant gets a Pinecone namespace (or a `tenant_id` filter in pgvector), so a single index serves thousands of customers without cross-contamination — the same isolation principle from Day 4's sharding discussion, applied to vectors.
- **pgvector vs. Pinecone tradeoff**: AESP started on pgvector because ticket and knowledge-base data already lived in Postgres, keeping vector search transactionally consistent with the rest of the schema. As the knowledge base grew past tens of millions of chunks across tenants, latency at high recall pushed heavier tenants onto Pinecone, while smaller tenants stayed on pgvector — a hybrid approach driven by scale, not dogma.
- **Similarity threshold as a guardrail**: the Resolver Agent never uses a retrieved chunk below a similarity cutoff (e.g., 0.75) — this prevents the "confidently wrong" failure mode where a weakly related chunk gets stitched into a reply as if it were authoritative.
- **Re-embedding on model upgrade**: when the embedding model version changes, old and new vectors are *not* comparable — AESP has to re-embed the entire knowledge base and cut over atomically, which is treated as a versioned migration, not a background job.

---

## 5. Interview Q&A

**Q1: Why can't you just use a regular SQL index for nearest-neighbor search over embeddings?**
A: A B-tree index is built for exact-match and range queries on scalar values. Finding the closest vectors in a high-dimensional space requires comparing distance across many dimensions, which a B-tree can't represent. Vector databases use specialized structures like HNSW graphs or IVF clusters built specifically for approximate nearest-neighbor search at scale.

**Q2: What's the difference between exact and approximate nearest neighbor search, and why does production RAG almost always use ANN?**
A: Exact NN compares the query vector against every stored vector — accurate but O(n), too slow past a few hundred thousand vectors. ANN (e.g., HNSW) trades a small, tunable amount of recall for orders-of-magnitude faster lookups, which is the only way to hit sub-100ms retrieval latency at millions-to-billions of vectors.

**Q3: How does chunk size affect retrieval quality, and how would you choose one for a support knowledge base?**
A: Chunks too small lose surrounding context and can be ambiguous out of context; chunks too large dilute the embedding across multiple ideas, hurting match precision and wasting the LLM's context window on irrelevant text. For a support KB, I'd start with semantic chunking at the section/paragraph level with modest overlap, then tune based on retrieval eval metrics rather than picking a size arbitrarily.

**Q4: How would you design multi-tenant isolation in a shared vector database?**
A: Use per-tenant namespaces (Pinecone) or a mandatory `tenant_id` filter enforced at the query layer (pgvector), never relying on the application to "remember" to filter. I'd also add a test that specifically checks cross-tenant leakage doesn't occur under concurrent load.

**Q5: What happens if you switch embedding models — can you just start using the new model on your existing vector index?**
A: No — embeddings from different models (or even different versions of the same model) live in incompatible vector spaces; distances between an old vector and a new vector are meaningless. You have to re-embed the full corpus with the new model and cut over as an atomic migration, similar to a schema migration with a backfill.

---
