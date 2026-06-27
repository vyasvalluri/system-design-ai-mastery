# Day 3 — Caching: Eviction Policies, Write Strategies & Failure Patterns

> Learning approach: **Analogy first → Concept → Java code → Node.js code → Interview Q&A**
> AESP context: Cache LLM responses, ticket metadata, and session data to cut DB load by 80% and slash AI API costs by 40–60%.

---

## The analogy

You're a doctor seeing 100 patients a day. For every patient, you look up their blood type in a filing cabinet across the hospital — slow and exhausting. So you keep a **sticky note pad on your desk** with the last 20 patients' blood types. 80% of lookups now take 2 seconds instead of 2 minutes.

Your desk has limited space. When it's full and a new patient comes in, you erase someone. **Which one?** That's your eviction policy.

---

## Cache hit vs miss

```
Cache HIT:  Client → App → Redis → return data          (~1ms)
Cache MISS: Client → App → Redis (miss) → DB → Redis (write) → return data   (~100ms)
```

Target: **cache hit rate > 80%** for production systems. Below 80%, the caching overhead may not justify the complexity.

---

## Eviction policies

| Policy | What it evicts | Best for |
|---|---|---|
| **LRU** (Least Recently Used) | Least recently accessed key | General purpose — most common |
| **LFU** (Least Frequently Used) | Least accessed over time | Hot content (trending tickets, frequent queries) |
| **TTL** (Time-To-Live) | Keys after N seconds regardless of access | Session data, short-lived facts |
| **FIFO** | Oldest inserted key | Simple queue-like workloads |
| **Random** | Random key | Approximates LRU with much lower overhead |

Redis default: **LRU** with `maxmemory-policy allkeys-lru`.

### LRU implementation (Java)

```java
public class LRUCache<K, V> {
    private final int capacity;
    // LinkedHashMap maintains insertion order + access order
    private final LinkedHashMap<K, V> cache;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        // accessOrder=true: accessing a key moves it to the tail (most recent)
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity; // Evict when over capacity
            }
        };
    }

    public synchronized V get(K key) {
        return cache.getOrDefault(key, null);
    }

    public synchronized void put(K key, V value) {
        cache.put(key, value);
    }
}

// Usage
LRUCache<String, User> userCache = new LRUCache<>(1000);
userCache.put("user:123", user);
User cached = userCache.get("user:123");
```

---

## Write strategies

| Strategy | Flow | Tradeoff |
|---|---|---|
| **Cache-aside** (lazy loading) | App checks cache → miss → query DB → write cache | Most flexible. Cache only contains what's been read. Brief inconsistency after writes. |
| **Write-through** | Write to cache AND DB simultaneously | Always consistent. Slower writes (two round trips). |
| **Write-behind** (write-back) | Write to cache first, flush to DB async | Fastest writes. Risk of data loss if cache fails before flush. |
| **Write-around** | Skip cache on write, only cache on reads | Good when writes are rarely re-read. Avoids cache pollution. |

**AESP strategy per data type:**

| Data | Strategy | Reason |
|---|---|---|
| User sessions | Cache-aside + TTL 15min | Simple, tolerate brief staleness |
| LLM responses (RAG) | Write-through | Consistency critical, reads >> writes |
| Ticket metadata | Cache-aside + invalidate on update | Fast reads, explicit invalidation on change |
| Agent tool results | Write-behind | Speed matters, short TTL limits loss exposure |

---

## Cache-aside pattern — full implementation

### Node.js (ioredis)

```javascript
const Redis = require('ioredis');
const redis = new Redis();
const TTL = 300; // 5 minutes

async function getUserById(userId) {
  const cacheKey = `user:${userId}`;

  // 1. Check cache
  const cached = await redis.get(cacheKey);
  if (cached) {
    return JSON.parse(cached); // Cache HIT — ~1ms
  }

  // 2. Cache MISS → query DB
  const user = await db.users.findOne({ id: userId });
  if (!user) throw new Error(`User ${userId} not found`);

  // 3. Write to cache with TTL
  await redis.setex(cacheKey, TTL, JSON.stringify(user));
  return user;
}

async function updateUser(user) {
  await db.users.update(user);
  await redis.del(`user:${user.id}`); // Invalidate cache on update
}
```

### Java (Spring Boot + Jedis)

```java
@Service
public class UserService {
    private final JedisPool jedisPool;
    private final UserRepository userRepo;
    private static final int TTL_SECONDS = 300;

    public User getUserById(String userId) {
        String cacheKey = "user:" + userId;

        // 1. Check cache
        try (Jedis jedis = jedisPool.getResource()) {
            String cached = jedis.get(cacheKey);
            if (cached != null) {
                return deserialize(cached); // Cache HIT
            }
        }

        // 2. Cache MISS → query DB
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));

        // 3. Write to cache with TTL
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(cacheKey, TTL_SECONDS, serialize(user));
        }

        return user;
    }

    public void updateUser(User user) {
        userRepo.save(user);
        // Invalidate cache on update
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("user:" + user.getId());
        }
    }
}
```

### Spring @Cacheable (declarative)

```java
@Service
public class TicketService {

    @Cacheable(value = "tickets", key = "#ticketId", unless = "#result == null")
    public Ticket getTicket(String ticketId) {
        return ticketRepo.findById(ticketId).orElse(null);
        // Spring auto-checks Redis before calling this method
    }

    @CacheEvict(value = "tickets", key = "#ticket.id")
    public void updateTicket(Ticket ticket) {
        ticketRepo.save(ticket);
        // Spring auto-deletes cache entry after this method runs
    }

    @CachePut(value = "tickets", key = "#ticket.id")
    public Ticket createTicket(Ticket ticket) {
        return ticketRepo.save(ticket);
        // Always executes AND updates cache (write-through)
    }
}
```

---

## Cache failure patterns

### 1. Cache stampede (thundering herd)

**Problem**: Cache key expires. 10,000 concurrent requests all get a cache miss simultaneously. All hit the DB at once. DB crashes.

**Fix: Mutex lock**

```javascript
async function getUserWithLock(userId) {
  const cacheKey = `user:${userId}`;
  const lockKey  = `lock:${cacheKey}`;

  const cached = await redis.get(cacheKey);
  if (cached) return JSON.parse(cached);

  // NX = set only if not exists (atomic lock acquisition)
  const locked = await redis.set(lockKey, '1', 'EX', 5, 'NX');

  if (locked) {
    const user = await db.users.findOne({ id: userId });
    await redis.setex(cacheKey, 300, JSON.stringify(user));
    await redis.del(lockKey);
    return user;
  } else {
    // Another thread is fetching — wait and retry
    await new Promise(r => setTimeout(r, 100));
    return getUserWithLock(userId);
  }
}
```

**Fix: TTL jitter** — add random offset so keys don't all expire at the same moment:

```javascript
const BASE_TTL = 300;
const jitter = Math.floor(Math.random() * 60); // 0–60s random offset
await redis.setex(cacheKey, BASE_TTL + jitter, JSON.stringify(data));
```

### 2. Cache avalanche

**Problem**: Many keys expire simultaneously (e.g. all set at startup with the same TTL). Entire DB floods.

**Fix**: TTL jitter per key (see above). Also: never set all keys at the same time during a warm-up.

### 3. Cache penetration

**Problem**: Attacker queries keys that don't exist anywhere (e.g. `user?id=99999999`). Cache always misses → DB always hit.

**Fix A — Cache null values**:
```javascript
const user = await db.users.findOne({ id: userId });
// Cache even null results with short TTL
await redis.setex(cacheKey, 30, JSON.stringify(user ?? '__NULL__'));
```

**Fix B — Bloom filter** (probabilistic, memory-efficient):
```javascript
const BloomFilter = require('bloom-filter');
const bloom = new BloomFilter(1000000, 0.01); // 1M items, 1% false positive rate

// On startup: populate with all known user IDs
const allIds = await db.users.findAllIds();
allIds.forEach(id => bloom.add(`user:${id}`));

// On request: check Bloom filter before any DB query
async function getUserById(userId) {
  if (!bloom.has(`user:${userId}`)) {
    return null; // Definitely doesn't exist — skip DB entirely
  }
  // May exist — proceed with cache-aside
  return getUserFromCacheOrDB(userId);
}
```

### 4. Hot key problem

**Problem**: One key (e.g. `product:iphone-16`) gets 95% of all traffic. Single Redis node becomes a bottleneck.

**Fix A — Local cache in front of Redis**:
```javascript
const NodeCache = require('node-cache');
const localCache = new NodeCache({ stdTTL: 5 }); // 5s local TTL

async function getProduct(productId) {
  // L1: process-local cache (nanoseconds)
  const local = localCache.get(productId);
  if (local) return local;

  // L2: Redis (milliseconds)
  const cached = await redis.get(`product:${productId}`);
  if (cached) {
    const data = JSON.parse(cached);
    localCache.set(productId, data);
    return data;
  }

  // L3: DB
  const product = await db.products.findById(productId);
  await redis.setex(`product:${productId}`, 300, JSON.stringify(product));
  localCache.set(productId, product);
  return product;
}
```

**Fix B — Key replication**: Store hot key as `product:iphone-16:shard:0` through `product:iphone-16:shard:N`, pick a random shard on read. Spreads traffic across N Redis nodes.

---

## Interview Q&A

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

## AESP cache key design

```
session:{token}                    → User session (TTL: 15min)
user:{id}                          → User profile (TTL: 5min, invalidate on update)
ticket:{id}                        → Ticket data (TTL: 5min, invalidate on update)
rag:{sha256(query+context)}        → LLM response (TTL: 1hr)
tool:{sha256(tool+params)}         → Agent tool result (TTL: 30s)
product:{id}:shard:{0..9}          → Hot product (sharded across 10 nodes)
lock:{key}                         → Stampede lock (TTL: 5s, auto-expire)
ratelimit:{userId}                 → Rate limit counter (TTL: window size)
```

---

## Day 3 checklist

- [ ] Explain LRU vs LFU vs TTL and when to choose each
- [ ] Implement cache-aside pattern from memory in both Java and Node.js
- [ ] Explain write-through vs write-behind tradeoffs with examples
- [ ] Describe all 4 failure patterns (stampede, avalanche, penetration, hot key) and their fixes
- [ ] Implement mutex lock for stampede prevention
- [ ] Explain Bloom filters and when to use them
- [ ] Know when NOT to cache
- [ ] Design cache key schema for AESP
