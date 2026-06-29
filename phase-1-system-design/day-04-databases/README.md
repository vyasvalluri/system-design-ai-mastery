# Day 4 — Databases: Indexing, Sharding & Replication

> Learning approach: **Analogy first → Concept → Java code → Node.js code → Interview Q&A**
> AESP context: PostgreSQL for tickets/users/orgs (ACID, relational). Redis for cache/sessions. Pinecone/pgvector for embeddings (RAG). Each DB for what it does best.

---

## The analogy

**Indexing** — A library with 10 million books. Without an index you walk every aisle, scan every spine — O(n). With a card catalogue you check *"Martin, Robert → Aisle 47, Shelf 3"* — two lookups, done. That's a B-tree index — O(log n).

**Sharding** — The library gets so popular one building can't hold everyone. You open 3 branches: A–F in Branch 1, G–M in Branch 2, N–Z in Branch 3. Each branch handles its own readers independently. That's horizontal sharding.

**Replication** — You photocopy every book and keep copies in a backup vault. If the main library burns down, the vault opens next morning. That's replication.

---

## Part 1 — Indexing

### B-tree index (the default)

Every `CREATE INDEX` in PostgreSQL/MySQL creates a B-tree unless specified otherwise. Balanced tree of sorted keys. Every lookup is O(log n) regardless of table size.

```sql
-- Without index: full table scan (10M rows scanned for 1 result)
SELECT * FROM tickets WHERE user_id = 'u_123';

-- Create index
CREATE INDEX idx_tickets_user_id ON tickets(user_id);

-- Now: 3 B-tree comparisons → row pointer → fetch row
-- Query plan shows: Index Scan instead of Seq Scan
EXPLAIN SELECT * FROM tickets WHERE user_id = 'u_123';
```

### Composite index — column order matters

```sql
-- AESP: find open tickets for a user, sorted by created_at
CREATE INDEX idx_tickets_user_status_created
    ON tickets(user_id, status, created_at);

-- USES the index (leading columns match)
SELECT * FROM tickets
WHERE user_id = 'u_123' AND status = 'open'
ORDER BY created_at DESC;

-- Does NOT use the index (skips leading column)
SELECT * FROM tickets WHERE status = 'open';
```

Rule: **most selective column first**, match the WHERE clause left-to-right.

### Covering index

Include all columns a query needs so the DB never touches the main table:

```sql
-- Query needs user_id, status, created_at, subject — nothing else
CREATE INDEX idx_tickets_covering
    ON tickets(user_id, status)
    INCLUDE (created_at, subject);

-- Entire query satisfied from the index alone (Index Only Scan)
SELECT subject, created_at FROM tickets
WHERE user_id = 'u_123' AND status = 'open';
```

### Index types

| Type | Structure | Best for |
|---|---|---|
| **B-tree** | Sorted balanced tree | Equality, range, ORDER BY |
| **Hash** | Hash table | Equality only (`=`), faster than B-tree for exact match |
| **GIN** | Inverted index | Full-text search, JSONB, arrays |
| **GiST** | Generalized search tree | Geometric data, fuzzy search |
| **BRIN** | Block Range Index | Huge append-only tables (logs, time-series) — tiny size |

### Index anti-patterns

```sql
-- ❌ Index on low-cardinality column → useless (only 2 values)
CREATE INDEX ON tickets(is_deleted);  -- query planner ignores it

-- ❌ Index on every column → write amplification
-- Every INSERT/UPDATE/DELETE must update ALL indexes → slows writes 5–10x

-- ❌ Implicit type cast kills the index
SELECT * FROM tickets WHERE user_id = 123;  -- user_id is VARCHAR, 123 is INT
-- DB casts every row → full table scan despite index existing
```

---

## Part 2 — Sharding

When a single DB node can't handle data volume or write throughput, you shard — split data across multiple independent DB instances.

### Sharding strategies

**Range sharding** — partition by value ranges:
```
Shard 1: user_id       1 –   1,000,000
Shard 2: user_id 1,000,001 –  2,000,000
Shard 3: user_id 2,000,001 –  3,000,000
```
Simple, supports range queries. Risk: hotspot if new users cluster in one shard.

**Hash sharding** — `shard = hash(user_id) % N`:
```
user_id 'u_abc' → hash → shard 2
user_id 'u_xyz' → hash → shard 0
```
Even distribution. Problem: range queries hit all shards; adding a shard remaps every key.

**Consistent hashing** — maps both data and nodes onto a ring. Adding a node only remaps `1/N` of keys. Used by Cassandra, DynamoDB, Redis Cluster.

**Directory sharding** — a lookup table maps each key to its shard:
```
user_id → shard_id stored in a routing table (Redis or separate DB)
```
Most flexible. Single point of failure if the routing table goes down.

### Java: shard routing

```java
@Service
public class ShardRouter {
    private final List<DataSource> shards; // 3 DB connections

    public DataSource getShardForUser(String userId) {
        int shardIndex = Math.abs(userId.hashCode()) % shards.size();
        return shards.get(shardIndex);
    }

    public Ticket getTicket(String ticketId, String userId) {
        DataSource shard = getShardForUser(userId);
        try (Connection conn = shard.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM tickets WHERE id = ? AND user_id = ?");
            stmt.setString(1, ticketId);
            stmt.setString(2, userId);
            ResultSet rs = stmt.executeQuery();
            return mapToTicket(rs);
        }
    }
}
```

### Node.js: shard routing

```javascript
const shards = [
  knex({ connection: process.env.DB_SHARD_0 }),
  knex({ connection: process.env.DB_SHARD_1 }),
  knex({ connection: process.env.DB_SHARD_2 }),
];

function getShardForUser(userId) {
  // FNV hash for better distribution than JS built-in
  let hash = 2166136261;
  for (const char of userId) {
    hash ^= char.charCodeAt(0);
    hash = (hash * 16777619) >>> 0;
  }
  return shards[hash % shards.length];
}

async function getTicketsByUser(userId) {
  const db = getShardForUser(userId);
  return db('tickets')
    .where({ user_id: userId, status: 'open' })
    .orderBy('created_at', 'desc');
}
```

### Cross-shard queries — the hard problem

```sql
-- This query CANNOT run on one shard:
SELECT COUNT(*) FROM tickets WHERE status = 'open';

-- You must scatter-gather: query all shards, merge results in app
-- → Expensive, complex, inconsistent (different shards at different points in time)
```

Rule: design your shard key so **90%+ of queries hit exactly one shard**. If you frequently need cross-shard queries, your shard key is wrong.

---

## Part 3 — Replication

### Primary-Replica

One primary accepts writes. Replicas receive changes via WAL (Write-Ahead Log) streaming and serve reads.

```
App writes → Primary
              ↓ WAL stream (async or sync)
         Replica 1, Replica 2, Replica 3
App reads → any replica (load balanced)
```

AESP: 3 read replicas. Ticket reads go to replicas. Ticket writes go to primary. The AI query pipeline (read-heavy) queries replicas — 3× read throughput at no extra write cost.

### Synchronous vs asynchronous replication

| | Async (default) | Sync |
|---|---|---|
| Write speed | Fast (commit, then replicate) | Slower (wait for replica ACK) |
| Data loss on failover | Up to replication lag (10–100ms) | Zero (replica confirmed write) |
| Availability | High (primary doesn't wait) | Lower (write blocked if replica down) |
| Use case | Read scaling, analytics replicas | Financial data, zero data loss requirement |

### Java: replica-aware datasource

```java
@Configuration
public class DatabaseConfig {

    @Bean("primaryDataSource")
    public DataSource primaryDataSource() {
        return DataSourceBuilder.create()
            .url(env.getProperty("db.primary.url"))
            .build();
    }

    @Bean("replicaDataSource")
    public DataSource replicaDataSource() {
        return new RoundRobinDataSource(List.of(
            env.getProperty("db.replica1.url"),
            env.getProperty("db.replica2.url"),
            env.getProperty("db.replica3.url")
        ));
    }
}

@Service
public class TicketService {

    @Transactional("primaryTransactionManager")
    public Ticket createTicket(TicketRequest req) { /* writes to primary */ }

    @Transactional(value = "replicaTransactionManager", readOnly = true)
    public List<Ticket> getOpenTickets(String userId) { /* reads from replica */ }
}
```

### Node.js: replica-aware queries

```javascript
const primary = knex({ connection: process.env.DB_PRIMARY });
const replicas = [
  knex({ connection: process.env.DB_REPLICA_1 }),
  knex({ connection: process.env.DB_REPLICA_2 }),
  knex({ connection: process.env.DB_REPLICA_3 }),
];
let replicaIdx = 0;
const replica = () => replicas[replicaIdx++ % replicas.length];

// Writes → primary
async function createTicket(data) {
  return primary('tickets').insert(data).returning('*');
}

// Reads → replica (round-robin)
async function getTickets(userId) {
  return replica()('tickets')
    .where({ user_id: userId })
    .orderBy('created_at', 'desc');
}

// WARNING: Read-your-own-writes problem
// User creates ticket → goes to primary
// User immediately lists tickets → might hit replica (not yet synced)
// Fix: route reads to primary for 500ms after a write, or use session stickiness
```

---

## SQL vs NoSQL — when to choose

| | SQL (PostgreSQL) | NoSQL (MongoDB/Cassandra/DynamoDB) |
|---|---|---|
| Schema | Fixed, enforced | Flexible, document/key-value |
| ACID | Full transactions | Eventual consistency (usually) |
| Relationships | JOINs, foreign keys | Denormalize, embed documents |
| Scaling writes | Hard (shard manually) | Built-in horizontal scaling |
| Query flexibility | Arbitrary SQL | Limited to access patterns |
| Best for | Financial, ERP, most apps | High-volume, schema-evolving, global |

---

## AESP database schema

```sql
-- Users (low volume, complex relationships → PostgreSQL)
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) UNIQUE NOT NULL,
    org_id      UUID REFERENCES orgs(id),
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Tickets (high volume, main query table)
CREATE TABLE tickets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id),
    org_id      UUID NOT NULL REFERENCES orgs(id),
    status      VARCHAR(20) NOT NULL DEFAULT 'open',
    priority    SMALLINT DEFAULT 2,
    subject     TEXT NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Critical indexes for AESP query patterns
CREATE INDEX idx_tickets_user_status    ON tickets(user_id, status);
CREATE INDEX idx_tickets_org_created    ON tickets(org_id, created_at DESC);
CREATE INDEX idx_tickets_priority_open  ON tickets(priority, status) WHERE status = 'open';
-- ↑ Partial index: only indexes open tickets → smaller, faster

-- AI interaction log (append-only, huge → BRIN index)
CREATE TABLE ai_interactions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id   UUID NOT NULL REFERENCES tickets(id),
    prompt_hash VARCHAR(64),
    tokens_used INT,
    latency_ms  INT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- BRIN index: tiny (1000× smaller than B-tree) for append-only time-series
CREATE INDEX idx_ai_interactions_created_brin
    ON ai_interactions USING BRIN (created_at);
```

---

## Interview Q&A

**Q: "How does a B-tree index work and when would you not use one?"**

> A B-tree is a self-balancing sorted tree. At each node, values are compared to navigate left (smaller) or right (larger). Leaf nodes hold the actual row pointers. Any lookup is O(log n) — for 10 million rows, that's about 24 comparisons. Skip a B-tree for: low-cardinality columns like boolean flags (planner does a full scan instead anyway); columns with heavy write load where index maintenance dominates; and full-text search (use GIN instead). Also avoid indexing every column — each index adds ~10–30% overhead to every INSERT/UPDATE/DELETE.

**Q: "How would you shard the AESP ticket database?"**

> Shard key: `org_id`. Rationale: enterprise support queries are almost always org-scoped ("show me all open tickets for my org") — so 95% of queries hit exactly one shard. Avoid user_id as shard key because cross-user analytics (org dashboards) would require scatter-gather across all shards. Use consistent hashing to distribute orgs across shards so adding a new shard only remaps ~1/N of orgs. Put the shard routing table in Redis for sub-millisecond lookup. One danger: large enterprise orgs with millions of tickets become hot shards — handle with sub-sharding by `org_id + ticket_date_bucket`.

**Q: "Explain replication lag and how it causes bugs."**

> Replication lag is the time between a write committing on the primary and that change appearing on a replica — typically 10–100ms in async replication. The classic bug: user creates an account (write → primary), is immediately redirected to their dashboard (read → replica), and sees "no account found" because the replica hasn't received the write yet. Fixes: (1) route reads to primary for 500ms after any write for that session; (2) use synchronous replication for critical data; (3) track a replication position token — if the replica's position is behind the write's LSN, route to primary instead.

**Q: "What's the CAP theorem and how does it apply to database choices?"**

> CAP: you can only guarantee two of — Consistency (all nodes see same data), Availability (every request gets a response), Partition tolerance (system works despite network splits). Network partitions happen in any distributed system, so the real choice is CP vs AP. PostgreSQL with synchronous replication: CP — prioritizes consistency, may reject writes during partition. Cassandra/DynamoDB: AP — always available, may return stale data. For AESP: tickets need CP (you don't want two agents updating the same ticket with conflicting state). Analytics/AI logs can be AP (eventual consistency is fine for reporting).

---

## Day 4 checklist

- [ ] Explain B-tree index structure and why lookups are O(log n)
- [ ] Write a composite index and explain column order rules
- [ ] Name 5 index types (B-tree, Hash, GIN, GiST, BRIN) and their use cases
- [ ] Explain 3 sharding strategies: range, hash, consistent hashing
- [ ] Implement shard routing in Java and Node.js from memory
- [ ] Describe async vs sync replication tradeoffs
- [ ] Explain the read-your-own-writes problem and 2 fixes
- [ ] Explain CAP theorem with concrete AESP examples
- [ ] Design the AESP ticket table schema with appropriate indexes
