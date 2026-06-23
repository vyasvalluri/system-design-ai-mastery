# Day 1 — APIs: REST vs GraphQL vs gRPC

> Learning approach: **Analogy first → Concept → Java code → Node.js code → Interview Q&A**
> AESP context: The gateway layer serves three client types. Picking the right protocol per client is the first real design decision.

---

## The restaurant analogy

| Protocol | Analogy | When to use in AESP |
|---|---|---|
| REST | Fixed-menu waiter — order a named dish, get the whole dish | External devs, webhooks, email ingestion |
| GraphQL | Build-your-own bowl (Chipotle) — specify exactly what you want | Web dashboard, mobile app |
| gRPC | Kitchen walkie-talkie — chefs talking shorthand, fast and coded | Service-to-service internal calls |

---

## REST — The fixed-menu waiter

You walk into a restaurant, point at item 4 on the menu, and the waiter brings you the whole dish. You can't ask for just the sauce from item 4 and the chicken from item 7. That's REST — predefined resources, predefined responses.

**Resources are nouns. HTTP verbs are actions.**
```
GET    /api/v1/tickets          → read tickets
POST   /api/v1/tickets          → create a ticket
PATCH  /api/v1/tickets/:id      → update a ticket
DELETE /api/v1/tickets/:id      → delete a ticket
```

**The over-fetching problem:** When you order pasta, you get the whole plate. In REST, `GET /tickets/123` returns 30 fields even if your screen only needs 2. That's wasted bandwidth — especially painful on mobile.

### AESP implementation — Node.js (Express)

```javascript
router.post('/tickets', authenticate, async (req, res) => {
  const { subject, description, priority } = req.body;

  // Idempotency: like a receipt number — submit twice, get same ticket back
  const key = req.headers['idempotency-key'];
  const cached = await redis.get(`idem:${key}`);
  if (cached) return res.status(200).json(JSON.parse(cached));

  const ticket = await db.tickets.create({
    subject, description,
    priority: priority || 'MEDIUM',
    tenantId: req.user.tenantId,
    status: 'OPEN'
  });

  await redis.setex(`idem:${key}`, 86400, JSON.stringify(ticket));
  await kafka.publish('ticket.created', { ticketId: ticket.id });

  return res.status(201).json({ data: ticket });
});

// Cursor pagination — NOT page numbers
router.get('/tickets', authenticate, async (req, res) => {
  const { cursor, limit = 20 } = req.query;

  // cursor = "give me everything after this ticket ID"
  // Safe when new tickets are inserted — unlike page numbers
  const tickets = await db.tickets.findMany({
    where: { tenantId: req.user.tenantId,
             ...(cursor && { id: { gt: cursor } }) },
    take: Number(limit) + 1,
    orderBy: { createdAt: 'desc' }
  });

  const hasMore = tickets.length > Number(limit);
  if (hasMore) tickets.pop();

  res.json({ data: tickets,
             pagination: { nextCursor: hasMore ? tickets.at(-1).id : null } });
});
```

### AESP implementation — Java (Spring Boot)

```java
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    @PostMapping
    public ResponseEntity<TicketResponse> createTicket(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateTicketRequest request,
            @AuthenticationPrincipal UserDetails user) {

        // Idempotency check
        String cached = redisTemplate.opsForValue().get("idem:" + idempotencyKey);
        if (cached != null) {
            return ResponseEntity.ok(objectMapper.readValue(cached, TicketResponse.class));
        }

        Ticket ticket = ticketService.create(request, user.getTenantId());

        redisTemplate.opsForValue().set(
            "idem:" + idempotencyKey,
            objectMapper.writeValueAsString(ticket),
            Duration.ofHours(24)
        );
        kafkaTemplate.send("ticket.created", new TicketCreatedEvent(ticket.getId()));

        return ResponseEntity.status(HttpStatus.CREATED).body(new TicketResponse(ticket));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<TicketResponse>> getTickets(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal UserDetails user) {

        Specification<Ticket> spec = TicketSpec.byTenant(user.getTenantId())
            .and(cursor != null ? TicketSpec.afterCursor(cursor) : null);

        List<Ticket> tickets = ticketRepository.findAll(spec,
            PageRequest.of(0, limit + 1, Sort.by("createdAt").descending()));

        boolean hasMore = tickets.size() > limit;
        if (hasMore) tickets.remove(tickets.size() - 1);
        String nextCursor = hasMore ? tickets.get(tickets.size() - 1).getId() : null;

        return ResponseEntity.ok(new PagedResponse<>(tickets, nextCursor));
    }
}
```

**Three REST rules senior engineers apply without thinking:**
1. Idempotency keys on every POST — clients always retry
2. Cursor pagination, never offset — offset breaks on insertion
3. Version from day one (`/v1/`) — never silently break callers

---

## GraphQL — The build-your-own bowl

At Chipotle, you walk down the line: "rice — yes, beans — no, extra guac, no onions." You get exactly what you asked for. No more, no less. That's GraphQL — the client defines the exact shape of the response.

**The N+1 problem — the inefficient waiter:**
A waiter takes orders from 100 people. He brings one plate, goes back, brings another plate, goes back 100 times. Catastrophically slow. That's what a naive GraphQL resolver does — makes 100 DB queries to fetch the agent for each of 100 tickets.

**DataLoader is the fix — collect all orders, make one trip:**

### Node.js (Apollo Server)

```javascript
// Schema — the menu of fields clients can ask for
const typeDefs = `
  type Ticket {
    id: ID!
    subject: String!
    status: String!
    assignedAgent: Agent      # causes N+1 without DataLoader
    comments(last: Int): [Comment!]!
  }
  type Query {
    ticket(id: ID!): Ticket
    tickets(cursor: String, limit: Int): TicketConnection!
  }
  type Subscription {
    ticketUpdated(id: ID!): Ticket!   # WebSocket real-time updates
  }
`;

const resolvers = {
  Ticket: {
    // WRONG — N+1: 100 tickets = 100 DB queries
    assignedAgent_WRONG: async (ticket) => db.agents.findById(ticket.agentId),

    // RIGHT — DataLoader batches all into ONE query
    assignedAgent: async (ticket, _, { loaders }) => {
      return loaders.agentLoader.load(ticket.agentId);
    }
  }
};

// DataLoader — the "batch tray" mechanism
const agentLoader = new DataLoader(async (agentIds) => {
  // Runs ONCE with all collected IDs: SELECT * FROM agents WHERE id IN (...)
  const agents = await db.agents.findMany({ where: { id: { in: agentIds } } });
  return agentIds.map(id => agents.find(a => a.id === id));
});
```

### Java (Netflix DGS framework)

```java
@DgsComponent
public class TicketDataFetcher {

    @DgsQuery
    public Ticket ticket(@InputArgument String id) {
        return ticketService.findById(id);
    }

    // BatchLoader — Java's DataLoader equivalent
    @DgsDataLoader(name = "agents")
    public BatchLoader<String, Agent> agentBatchLoader() {
        return agentIds -> CompletableFuture.supplyAsync(() -> {
            // Single DB query for all agent IDs
            Map<String, Agent> agentMap = agentRepository
                .findAllById(agentIds).stream()
                .collect(Collectors.toMap(Agent::getId, a -> a));
            return agentIds.stream().map(agentMap::get).collect(Collectors.toList());
        });
    }

    // Field resolver uses the batch loader — not a direct DB call
    @DgsData(parentType = "Ticket", field = "assignedAgent")
    public CompletableFuture<Agent> assignedAgent(DgsDataFetchingEnvironment env) {
        Ticket ticket = env.getSource();
        DataLoader<String, Agent> loader = env.getDataLoader("agents");
        return loader.load(ticket.getAgentId());   // batched automatically
    }
}
```

**Rule: Every GraphQL field that queries DB by foreign key MUST use DataLoader. No exceptions.**

---

## gRPC — The kitchen walkie-talkie

Two chefs use shorthand: "86 the salmon, re-fire on table 5." Fast, coded, only works if both know the system. That's gRPC — microservices talking binary shorthand defined in a shared `.proto` file.

**Why binary?** JSON `{"status": "RESOLVED"}` is 20 bytes as text. The same message in Protocol Buffers binary is ~4 bytes. At 10M tickets/day, that matters.

### The proto file — the shared codebook

```protobuf
syntax = "proto3";

service TicketService {
  rpc UpdateStatus(UpdateStatusRequest) returns (TicketResponse);
  rpc StreamResolutionProgress(TicketId) returns (stream ResolutionEvent);
}

message UpdateStatusRequest {
  string ticket_id   = 1;
  string status      = 2;
  string resolution  = 3;
  string resolved_by = 4;
}

message ResolutionEvent {
  string step       = 1;
  float  confidence = 2;
  bool   is_final   = 3;
}
```

### Node.js gRPC client (AI Service → Ticket Service)

```javascript
const client = new TicketServiceClient(
  'ticket-service:50051',           // internal DNS only — never exposed externally
  grpc.credentials.createInsecure() // mTLS in production via Istio service mesh
);

// Simple call — AI resolver marking ticket resolved
client.updateStatus({
  ticketId: 'tkt_abc123',
  status: 'RESOLVED',
  resolution: 'Refund will be processed in 3-5 business days.',
  resolvedBy: 'ai-resolver-agent-v2'
}, (err, response) => {
  if (err) throw err;
  console.log('Resolved:', response.status);
});

// Streaming call — AI agent streams progress steps live
const stream = client.streamResolutionProgress({ ticketId: 'tkt_abc123' });
stream.on('data', (event) => {
  console.log(`Step: ${event.step} | Confidence: ${event.confidence}`);
  if (event.isFinal) console.log('Resolution complete');
});
```

### Java gRPC server (Ticket Service implementing the contract)

```java
@GrpcService
public class TicketGrpcService extends TicketServiceGrpc.TicketServiceImplBase {

    @Override
    public void updateStatus(UpdateStatusRequest request,
                             StreamObserver<TicketResponse> responseObserver) {
        Ticket ticket = ticketService.updateStatus(
            request.getTicketId(), request.getStatus(),
            request.getResolution(), request.getResolvedBy()
        );

        responseObserver.onNext(TicketResponse.newBuilder()
            .setId(ticket.getId())
            .setStatus(ticket.getStatus().name())
            .build());
        responseObserver.onCompleted();
    }

    @Override
    public void streamResolutionProgress(TicketId request,
                                         StreamObserver<ResolutionEvent> stream) {
        List<String> steps = aiService.getResolutionSteps(request.getTicketId());
        for (int i = 0; i < steps.size(); i++) {
            stream.onNext(ResolutionEvent.newBuilder()
                .setStep(steps.get(i))
                .setConfidence(0.91f)
                .setIsFinal(i == steps.size() - 1)
                .build());
        }
        stream.onCompleted();
    }
}
```

**Important:** gRPC is internal only in AESP. mTLS handled by Istio service mesh — no manual cert management in application code.

---

## Decision rule — one question at a time

```
Is it service-to-service (internal)?
  YES → gRPC (fast, typed, binary)
  NO (client-facing) →
    Does the client need flexible/complex queries?
      YES → GraphQL (dashboard, mobile)
      NO  → REST (webhooks, external devs, simple integrations)

Bonus: Need real-time live updates?
  → GraphQL Subscriptions (WebSocket)
```

---

## HTTP status codes — know these cold

| Code | Meaning | When to use |
|---|---|---|
| 200 | OK | GET, PATCH success |
| 201 | Created | POST created a resource |
| 400 | Bad Request | Invalid input from client |
| 401 | Unauthorized | No/invalid auth token |
| 403 | Forbidden | Authenticated but not allowed |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | Duplicate / state conflict |
| 422 | Unprocessable | Valid format, invalid semantics |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Something broke on our side |

---

## Interview Q&A

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
