# Day 2 — Load Balancing & Rate Limiting

> Learning approach: **Analogy first → Concept → Java code → Node.js code → Interview Q&A**
> AESP context: The platform must handle thousands of concurrent support tickets and AI requests without a single server becoming a bottleneck.

---

## The analogy

**Load Balancing** — Imagine a busy airport with 10 security lanes. A dispatcher at the entrance counts how many people are in each lane and directs each new passenger to the shortest one. That dispatcher is your load balancer. Without it, lane 1 has 50 people and lane 10 is empty.

**Rate Limiting** — The same airport has a rule: each airline can only send 200 passengers per hour through security. No matter how important you are, you can't flood the system. That's rate limiting — protecting the backend from any single caller overwhelming it.

---

## Load Balancing

### Algorithms

| Algorithm | How it works | Best for |
|---|---|---|
| **Round Robin** | Requests go 1→2→3→1→2→3 in rotation | Stateless services, uniform request cost |
| **Least Connections** | New request goes to server with fewest active connections | Long-lived connections (WebSockets, streaming) |
| **IP Hash** | Hash of client IP determines server | Sticky sessions — same user always hits same server |
| **Weighted Round Robin** | Server 1 gets 3x traffic if it has 3x capacity | Heterogeneous server fleet |
| **Random** | Pick a server at random | Simple, surprisingly effective at scale |

### Layer 4 vs Layer 7

| | Layer 4 (Transport) | Layer 7 (Application) |
|---|---|---|
| Operates on | TCP/UDP packets | HTTP headers, URLs, cookies |
| Speed | Faster (no payload inspection) | Slower but smarter |
| Routing logic | IP + port only | `/api/tickets` → tickets cluster, `/api/agents` → AI cluster |
| Example | AWS NLB | AWS ALB, Nginx, HAProxy |
| AESP use | Raw throughput for high-volume ticket ingestion | Route AI requests to GPU-backed servers, REST to app servers |

### Health checks

A load balancer constantly pings each server. If a server fails to respond within the timeout, it's removed from rotation automatically.

```
Active health check:  LB → GET /health → 200 OK (every 10s)
Passive health check: LB watches for 5xx errors → auto-remove after 3 failures
```

### AESP implementation — Node.js (custom round-robin)

```javascript
class LoadBalancer {
  constructor(servers) {
    this.servers = servers;       // ['http://app1:3000', 'http://app2:3000']
    this.currentIndex = 0;
    this.healthStatus = new Map(servers.map(s => [s, true]));
  }

  getNextServer() {
    const healthy = this.servers.filter(s => this.healthStatus.get(s));
    if (healthy.length === 0) throw new Error('No healthy servers');

    const server = healthy[this.currentIndex % healthy.length];
    this.currentIndex++;
    return server;
  }

  async healthCheck() {
    for (const server of this.servers) {
      try {
        const res = await fetch(`${server}/health`, { timeout: 3000 });
        this.healthStatus.set(server, res.ok);
      } catch {
        this.healthStatus.set(server, false);
        console.warn(`Server ${server} marked unhealthy`);
      }
    }
  }
}

// Run health checks every 10 seconds
const lb = new LoadBalancer(['http://app1:3000', 'http://app2:3000', 'http://app3:3000']);
setInterval(() => lb.healthCheck(), 10_000);

// Proxy incoming request to next healthy server
app.use(async (req, res) => {
  const target = lb.getNextServer();
  const response = await fetch(`${target}${req.path}`, {
    method: req.method,
    headers: req.headers,
    body: req.body,
  });
  res.status(response.status).json(await response.json());
});
```

### AESP implementation — Java (Spring Cloud LoadBalancer)

```java
@Configuration
public class LoadBalancerConfig {

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

@Service
public class TicketService {
    private final RestTemplate restTemplate;

    // Spring Cloud auto-resolves "ticket-service" to a healthy instance
    public TicketResponse createTicket(TicketRequest request) {
        return restTemplate.postForObject(
            "http://ticket-service/api/v1/tickets",
            request,
            TicketResponse.class
        );
    }
}

// Weighted load balancing with @LoadBalancerClient
@Configuration
@LoadBalancerClient(name = "ai-service", configuration = AiServiceConfig.class)
public class AiServiceConfig {

    @Bean
    public ServiceInstanceListSupplier instanceSupplier(ConfigurableApplicationContext ctx) {
        return ServiceInstanceListSupplier
            .builder()
            .withWeighted()  // Weight based on server metadata
            .build(ctx);
    }
}
```

---

## Rate Limiting

### Algorithms

| Algorithm | How it works | Pros | Cons |
|---|---|---|---|
| **Fixed Window** | Count requests per minute bucket (e.g. 100/min resets at :00) | Simple | Burst at boundary: 100 at 0:59 + 100 at 1:00 = 200 in 2 seconds |
| **Sliding Window Log** | Keep timestamps of all requests, count last N seconds | Precise | High memory (stores every timestamp) |
| **Sliding Window Counter** | Blend current + previous window counts proportionally | Efficient + accurate | Slightly approximate |
| **Token Bucket** | Bucket fills at rate R, each request consumes 1 token | Allows controlled bursts | Complex to implement |
| **Leaky Bucket** | Requests queue; process at fixed rate regardless of arrival | Smooths traffic | Queue can grow; adds latency |

### AESP rate limiting tiers

```
Free tier:    10  requests/min  per user
Pro tier:    100  requests/min  per user
Enterprise: 1000  requests/min  per org
AI endpoints:  10  requests/min  per user  (expensive — GPU cost)
```

### AESP implementation — Node.js (sliding window with Redis)

```javascript
const redis = require('ioredis');
const client = new redis();

async function slidingWindowRateLimit(userId, limit, windowSeconds) {
  const now = Date.now();
  const windowStart = now - windowSeconds * 1000;
  const key = `ratelimit:${userId}`;

  const pipeline = client.pipeline();
  pipeline.zremrangebyscore(key, '-inf', windowStart); // Remove old entries
  pipeline.zadd(key, now, `${now}-${Math.random()}`); // Add current request
  pipeline.zcard(key);                                 // Count in window
  pipeline.expire(key, windowSeconds);                 // Auto-cleanup

  const results = await pipeline.exec();
  const requestCount = results[2][1];

  if (requestCount > limit) {
    const oldestRequest = await client.zrange(key, 0, 0, 'WITHSCORES');
    const retryAfter = Math.ceil((parseInt(oldestRequest[1]) + windowSeconds * 1000 - now) / 1000);
    throw { status: 429, retryAfter, message: `Rate limit exceeded. Retry after ${retryAfter}s` };
  }

  return { allowed: true, remaining: limit - requestCount };
}

// Express middleware
function rateLimitMiddleware(limit, windowSeconds) {
  return async (req, res, next) => {
    try {
      const result = await slidingWindowRateLimit(req.user.id, limit, windowSeconds);
      res.setHeader('X-RateLimit-Limit', limit);
      res.setHeader('X-RateLimit-Remaining', result.remaining);
      next();
    } catch (err) {
      if (err.status === 429) {
        res.setHeader('Retry-After', err.retryAfter);
        return res.status(429).json({ error: err.message });
      }
      next(err);
    }
  };
}

// Apply per route
app.post('/api/v1/ai/analyze', rateLimitMiddleware(10, 60), analyzeHandler);
app.post('/api/v1/tickets',    rateLimitMiddleware(100, 60), ticketHandler);
```

### AESP implementation — Java (token bucket with Bucket4j)

```java
@Component
public class RateLimitFilter implements Filter {

    private final LoadingCache<String, Bucket> buckets = Caffeine.newBuilder()
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build(this::createBucket);

    private Bucket createBucket(String userId) {
        // 100 tokens, refill 100 per minute
        Bandwidth limit = Bandwidth.classic(100,
            Refill.greedy(100, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
        throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String userId = extractUserId(request);
        Bucket bucket = buckets.get(userId);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.addHeader("X-RateLimit-Remaining",
                String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(req, res);
        } else {
            long retryAfterSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000;
            response.addHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.sendError(HttpServletResponse.SC_TOO_MANY_REQUESTS,
                "Rate limit exceeded");
        }
    }
}
```

---

## Failure patterns to know

| Failure | Cause | Fix |
|---|---|---|
| **Thundering herd** | All servers restart simultaneously, flood downstream | Stagger restarts; circuit breaker |
| **Hot server** | IP hash sends one client's huge traffic to one server | Consistent hashing with virtual nodes |
| **Rate limit bypass** | Attacker uses many IPs | Rate limit per user ID, not IP; add CAPTCHA |
| **Cascading failure** | Overloaded server slows → LB keeps sending more → crashes | Circuit breaker (Resilience4j / hystrix) |

---

## Interview Q&A

**Q: "How would you design rate limiting for a distributed system with 10 servers?"**

> Per-server counters won't work — a user could send 100 requests to each of 10 servers (1000 total) and never trigger any limit. Use a centralized Redis store with a sliding window algorithm. All servers share the same Redis counter per user. For extreme scale, use Redis Cluster and hash user IDs to specific shards. Add a small local counter (Caffeine cache) to absorb burst traffic and reduce Redis round trips — sync to Redis every 100ms. Accept slight over-counting in exchange for latency reduction.

**Q: "What's the difference between a load balancer and an API gateway?"**

> A load balancer distributes traffic across identical server instances — it's dumb about what the request contains. An API gateway is a Layer 7 intelligent router that also handles auth, rate limiting, SSL termination, request transformation, and routing to different microservices based on the URL path. In practice, you use both: the API gateway sits in front for smart routing and cross-cutting concerns, then load balancers sit in front of each service cluster for horizontal scaling.

**Q: "How does consistent hashing work and why is it used?"**

> Classic modular hashing (`server = hash(key) % N`) breaks when you add or remove a server — every key remaps. Consistent hashing maps both servers and keys onto a ring (0–2³²). A key goes to the nearest server clockwise on the ring. Adding a server only remaps the keys between the new server and its predecessor — typically `1/N` of total keys, not all of them. Critical for distributed caches (Redis Cluster), CDNs, and load balancers with session affinity.

---

## AESP connection

- **Load balancer config**: Route `/api/v1/ai/*` to GPU-enabled servers, `/api/v1/tickets` to standard app servers — Layer 7 path-based routing.
- **Rate limiting**: Protect AI endpoints (expensive per call) at 10 req/min for free tier, skip rate limiting for internal agent-to-agent calls using service tokens.
- **Health checks**: `/health` endpoint on every AESP service returns DB connection status, Redis ping, and memory usage — LB marks unhealthy within 30 seconds.

---

## Day 2 checklist

- [ ] Explain Round Robin vs Least Connections vs IP Hash and when to use each
- [ ] Describe Layer 4 vs Layer 7 load balancing with examples
- [ ] Implement sliding window rate limiter with Redis from memory
- [ ] Explain token bucket algorithm and how it allows controlled bursts
- [ ] Describe cascading failure and the circuit breaker pattern
- [ ] Know why consistent hashing matters for distributed systems
