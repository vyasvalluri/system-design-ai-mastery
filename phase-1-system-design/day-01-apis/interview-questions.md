# Day 1 — APIs: REST vs GraphQL vs gRPC — Interview Questions

> Extracted from `README.md` for standalone review/quizzing. See the full
> day README for the analogy, concept walkthrough, and code.


**Q: REST vs GraphQL — when to use each?**
REST = fixed menu (good for simple external APIs, CDN caching). GraphQL = custom order (good when UI needs data from multiple entities in one request). In AESP: REST for external developers, GraphQL for the dashboard that needs ticket + agent + AI summary + comments in one query.

**Q: What is the N+1 problem?**
Fetch 100 tickets → naive resolver makes 100 separate agent queries = 101 DB queries total. Fix: DataLoader collects all agent IDs in one event loop tick, fires one `SELECT ... WHERE id IN (...)`. In Java: `BatchLoader` with DGS. In Node.js: `dataloader` package.

**Q: When would you choose gRPC?**
Internal microservice calls where both sides are under your control and performance matters. Binary Protocol Buffers = 5–10x smaller than JSON. Strongly typed contract via `.proto` — compile-time errors instead of runtime surprises. Never expose gRPC to browsers — they can't speak HTTP/2 gRPC framing natively.

**Q: What is idempotency? Why does it matter?**
Same request → same result, no matter how many times called. Networks are unreliable — clients retry. Without idempotency on POST, a user submitting a ticket on a flaky network could create 3 tickets. Fix: client sends unique UUID as `Idempotency-Key` header, server caches result against that key, returns cached result on retry.

**Q: Cursor vs offset pagination?**
Offset pagination (`page=2`) breaks when rows are inserted between requests — you skip or repeat items. Cursor pagination (`cursor=last_id`) is a bookmark — "give me everything after this point." Insertion-safe and fast because it uses the primary key index. Always use cursor for production APIs.

---

*Next: [Day 2 — Load Balancing + Rate Limiting](../day-02-load-balancing/README.md)*
