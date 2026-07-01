# Day 3 — Caching: Eviction Policies, Write Strategies & Failure Patterns — Interview Questions

> Extracted from `README.md` for standalone review/quizzing. See the full
> day README for the analogy, concept walkthrough, and code.


**Q: "What's the difference between cache-aside and write-through?"**

> Cache-aside: the app manages cache manually — check cache, miss → DB, then write to cache. Cache only contains data that's actually been read. Write-through: every write goes to cache AND DB atomically. Always consistent but writes are slower. Use cache-aside for read-heavy workloads where you can tolerate brief staleness. Use write-through when you can't tolerate stale reads (financial data, session state).

**Q: "How would you handle a cache stampede at scale?"**

> Three approaches: (1) **Mutex lock** — first thread acquires a distributed Redis lock, fetches from DB, others wait and retry; (2) **Probabilistic early expiry** — probabilistically refresh keys before expiry based on computation cost × time-to-expire; (3) **TTL jitter** — add random offset to all TTLs so they never expire simultaneously. In practice, combine jitter (prevents avalanche) with mutex lock (handles individual key stampede).

**Q: "When would you NOT cache something?"**

> Real-time financial data (stock prices, balances) where stale data causes wrong decisions. Highly personalized per-user data that changes every request. Write-heavy data where invalidation overhead exceeds read benefit. Also skip caching if the dataset is small enough to fit in the DB's own query cache — adding Redis adds operational complexity for zero gain.

**Q: "What's a Bloom filter and how does it solve cache penetration?"**

> A Bloom filter is a probabilistic data structure that answers "definitely not in the set" or "probably in the set" — false negatives are impossible, false positives are possible (tunable, typically 1%). Before hitting the DB for a missing cache key, check the Bloom filter. If it says "not exists," skip the DB entirely. This eliminates the entire class of cache penetration attacks with ~10 bits per element regardless of element size.

**Q: "How do you cache LLM responses in an AI platform?"**

> Hash the normalized query + model + context window (excluding session-specific data) as the cache key. Store the full response in Redis with TTL matching the expected staleness tolerance (e.g. 1 hour for support knowledge base answers). Use semantic similarity (embedding cosine distance < 0.05) to also hit cache for paraphrased queries — this requires a vector-augmented cache layer. In AESP, this cuts OpenAI API costs by 40–60% for repeated support queries on the same topic.

---
