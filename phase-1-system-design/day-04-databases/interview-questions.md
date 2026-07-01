# Day 4 — Databases: Indexing, Sharding & Replication — Interview Questions

> Extracted from `README.md` for standalone review/quizzing. See the full
> day README for the analogy, concept walkthrough, and code.


**Q: "How does a B-tree index work and when would you not use one?"**

> A B-tree is a self-balancing sorted tree. At each node, values are compared to navigate left (smaller) or right (larger). Leaf nodes hold the actual row pointers. Any lookup is O(log n) — for 10 million rows, that's about 24 comparisons. Skip a B-tree for: low-cardinality columns like boolean flags (planner does a full scan instead anyway); columns with heavy write load where index maintenance dominates; and full-text search (use GIN instead). Also avoid indexing every column — each index adds ~10–30% overhead to every INSERT/UPDATE/DELETE.

**Q: "How would you shard the AESP ticket database?"**

> Shard key: `org_id`. Rationale: enterprise support queries are almost always org-scoped ("show me all open tickets for my org") — so 95% of queries hit exactly one shard. Avoid user_id as shard key because cross-user analytics (org dashboards) would require scatter-gather across all shards. Use consistent hashing to distribute orgs across shards so adding a new shard only remaps ~1/N of orgs. Put the shard routing table in Redis for sub-millisecond lookup. One danger: large enterprise orgs with millions of tickets become hot shards — handle with sub-sharding by `org_id + ticket_date_bucket`.

**Q: "Explain replication lag and how it causes bugs."**

> Replication lag is the time between a write committing on the primary and that change appearing on a replica — typically 10–100ms in async replication. The classic bug: user creates an account (write → primary), is immediately redirected to their dashboard (read → replica), and sees "no account found" because the replica hasn't received the write yet. Fixes: (1) route reads to primary for 500ms after any write for that session; (2) use synchronous replication for critical data; (3) track a replication position token — if the replica's position is behind the write's LSN, route to primary instead.

**Q: "What's the CAP theorem and how does it apply to database choices?"**

> CAP: you can only guarantee two of — Consistency (all nodes see same data), Availability (every request gets a response), Partition tolerance (system works despite network splits). Network partitions happen in any distributed system, so the real choice is CP vs AP. PostgreSQL with synchronous replication: CP — prioritizes consistency, may reject writes during partition. Cassandra/DynamoDB: AP — always available, may return stale data. For AESP: tickets need CP (you don't want two agents updating the same ticket with conflicting state). Analytics/AI logs can be AP (eventual consistency is fine for reporting).

---
