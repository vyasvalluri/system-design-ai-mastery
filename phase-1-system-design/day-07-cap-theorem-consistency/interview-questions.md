# Day 7 — CAP Theorem & Consistency Models — Interview Questions

> Extracted from `README.md` for standalone review/quizzing. See the full
> day README for the analogy, concept walkthrough, and code.


**Q: "Can a system be both CP and AP?"**

> Not for the same piece of data during an actual partition — by definition you choose one. But systems can offer tunable consistency per operation (Cassandra's per-query consistency level: ONE, QUORUM, ALL), and different subsystems within one architecture can independently be CP or AP, as in the AESP ticket-store-vs-cache split.

**Q: "Why isn't CA a real option?"**

> CA assumes the network never partitions, which only holds for a single node. Any system with more than one node communicating over a real network will eventually see a partition, so CA effectively only applies to non-distributed systems.

**Q: "What's the difference between CAP and PACELC, and why does PACELC matter more in practice?"**

> CAP only describes behavior during a partition, a relatively rare event. PACELC also covers the normal, non-partitioned case, where synchronous replication still trades latency for consistency. Since most of the time the system isn't partitioned, the latency/consistency tradeoff is the one engineers actually tune day to day.

**Q: "Explain quorum consistency — what does W + R > N actually buy you?"**

> With N replicas, write quorum W, and read quorum R, if W + R > N then every read quorum is guaranteed to overlap with the most recent write quorum on at least one node, so a read can never miss the latest write. If W + R ≤ N, that overlap isn't guaranteed — reads can return stale data, but writes and reads complete faster since fewer nodes need to respond.

**Q: "How would you explain eventual consistency to a non-technical stakeholder?"**

> If you update your profile picture, your friends might see the old one for a few seconds before it updates everywhere — but the system stays fast and available the whole time instead of freezing while it makes sure everyone agrees instantly.

**Q: "Give a concrete example of read-your-writes consistency mattering."**

> A user posts a comment and immediately refreshes — they must see their own comment even if other users briefly don't. Usually solved by routing a user's reads to the same replica/region they wrote to (sticky routing), rather than requiring full strong consistency for everyone.

---
