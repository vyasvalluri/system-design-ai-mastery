# Day 1 — APIs: REST vs GraphQL vs gRPC

> **AESP Context:** The API Gateway layer serves three different client types — each with different needs. Choosing the right protocol for each is the first real design decision in AESP.

---

## Mental model

Every system needs a **contract** between clients and servers. APIs are that contract. The question isn't "which is best" — it's "which fits this situation." Senior engineers choose protocols based on tradeoffs, not preference.

AESP uses all three protocols because it has three different client types with three different needs.

```
AESP Gateway routing rule:
  /graphql       →  GraphQL  (web dashboard, mobile app)
  /api/v1/*      →  REST     (external developers, webhooks, email ingestion)
  internal mesh  →  gRPC     (service-to-service, never exposed externally)
```

---

## REST — the foundation

REST is the default for external APIs. Resources are nouns, HTTP verbs are actions.

```
POST   /api/v1/tickets          → create a ticket
GET    /api/v1/tickets/:id      → get a ticket
PATCH  /api/v1/tickets/:id      → update a ticket
DELETE /api/v1/tickets/:id      → delete a ticket
GET    /api/v1/tickets?status=open&cursor=xyz  → list with pagination
```

### AESP implementation

```javascript
// POST /api/v1/tickets — create ticket with idempotency
router.post('/tickets', authenticate, rateLimit, async (req, res) => {
  const { subject, description, priority } = req.body;
  const tenantId = req.user.tenantId;

  // Idempotency: if client retries, don't create duplicate
  const existing = await redis.get(`idempotency:${req.headers['idempotency-key']}`);
  if (existing) return res.status(200).json(JSON.parse(existing));

  const ticket = await db.tickets.create({
    subject, description,
    priority: priority || 'medium',
    tenantId, status: 'open'
  });

  await redis.setex(`idempotency:${req.headers['idempotency-key']}`, 86400, JSON.stringify(ticket));
  await kafka.publish('ticket.created', { ticketId: ticket.id, tenantId });

  return res.status(201).json({ data: ticket });
});

// GET /api/v1/tickets — cursor-based pagination (never offset)
router.get('/tickets', authenticate, async (req, res) => {
  const { cursor, limit = 20, status } = req.query;

  const tickets = await db.tickets.findMany({
    where: {
      tenantId: req.user.tenantId,
      ...(status && { status }),
      ...(cursor && { id: { gt: cursor } })
    },
    take: Number(limit) + 1,
    orderBy: { createdAt: 'desc' }
  });

  const hasMore = tickets.length > Number(limit);
  if (hasMore) tickets.pop();

  return res.json({
    data: tickets,
    pagination: {
      nextCursor: hasMore ? tickets[tickets.length - 1].id : null,
      hasMore
    }
  });
});
```

### Three concepts senior engineers are judged on

**1. Idempotency keys**
Networks are unreliable. A client POSTs a ticket, the network drops before the response. Client retries. Without idempotency, you get a duplicate ticket. With it, the second request returns the same ticket created the first time.
- Client sends: `Idempotency-Key: <uuid-v4>` header
- Server stores result in Redis with that key for 24h
- On retry, return cached result without touching the DB

**2. Cursor vs offset pagination**
`?page=2&limit=20` breaks when rows are inserted mid-pagination — you skip or repeat items. Cursor pagination (`?cursor=last_seen_id`) says "give me everything after this point" — insertion-safe and fast because it uses the primary key index.

**3. API versioning**
Always version from day one: `/api/v1/`. When you need a breaking change, release `/api/v2/` and sunset v1 with 6 months' advance notice. Never silently break existing callers.

**HTTP status codes to know:**
`200 OK` · `201 Created` · `400 Bad Request` · `401 Unauthorized` · `403 Forbidden` · `404 Not Found` · `409 Conflict` · `422 Unprocessable Entity` · `429 Too Many Requests` · `500 Internal Server Error`

---

## GraphQL — flexible queries for complex UIs

The AESP dashboard shows a ticket + assigned agent + AI resolution + comments on one screen. With REST, that's 4+ round trips. With GraphQL, one query fetches exactly what the screen needs.

### AESP schema

```graphql
type Ticket {
  id: ID!
  subject: String!
  status: TicketStatus!
  priority: Priority!
  assignedAgent: Agent
  aiResolution: AIResolution
  comments(last: Int): [Comment!]!
  customer: Customer
}

type Query {
  ticket(id: ID!): Ticket
  tickets(filter: TicketFilter, cursor: String, limit: Int): TicketConnection!
}

type Subscription {
  ticketUpdated(id: ID!): Ticket!  # real-time via WebSocket
}
```

### The N+1 problem and DataLoader

```javascript
const resolvers = {
  Ticket: {
    // NAIVE — N+1: 100 tickets = 100 separate agent DB queries
    assignedAgent_NAIVE: async (ticket) => {
      return db.agents.findById(ticket.agentId);  // called once per ticket!
    },

    // CORRECT — DataLoader batches all agent IDs into ONE query
    assignedAgent: async (ticket, _, { loaders }) => {
      return loaders.agent.load(ticket.agentId);  // batched + cached
    }
  }
};

// DataLoader: collects all .load(id) calls in one event loop tick
// then fires a single SELECT * FROM agents WHERE id IN (...)
const agentLoader = new DataLoader(async (agentIds) => {
  const agents = await db.agents.findMany({
    where: { id: { in: agentIds } }
  });
  return agentIds.map(id => agents.find(a => a.id === id));
});
```

**Rule:** Every GraphQL field that queries the DB by a foreign key MUST use DataLoader. No exceptions.

---

## gRPC — internal service communication

Between AESP microservices, REST's JSON overhead adds up at scale. gRPC uses Protocol Buffers (binary): ~5x smaller payload, no string parsing, typed contract enforced at compile time.

### AESP proto definition

```protobuf
syntax = "proto3";

service TicketService {
  rpc CreateTicket(CreateTicketRequest) returns (TicketResponse);
  rpc UpdateStatus(UpdateStatusRequest) returns (TicketResponse);
  // AI agent streams real-time resolution steps
  rpc StreamResolutionProgress(TicketId) returns (stream ResolutionEvent);
}

message CreateTicketRequest {
  string subject     = 1;
  string description = 2;
  string tenant_id   = 3;
  Priority priority  = 4;
}

enum Priority {
  MEDIUM = 0;
  HIGH   = 1;
  P1     = 2;
}
```

```javascript
// AI Orchestration Service → Ticket Service via gRPC
const ticketClient = new TicketServiceClient('ticket-service:50051', credentials);

ticketClient.updateStatus({
  ticketId: ticket.id,
  status: 'RESOLVED',
  resolution: aiResponse,
  resolvedBy: 'ai-resolver-agent'
}, (err, response) => {
  if (err) throw err;
  console.log('Ticket resolved:', response.id);
});
```

**Important:** gRPC is internal only. Browsers can't speak HTTP/2 gRPC framing natively — never expose gRPC directly to clients.

---

## Decision matrix

| Situation | Use |
|---|---|
| External API for third-party developers | REST |
| Web/mobile dashboard with multi-entity data | GraphQL |
| Internal service → service calls | gRPC |
| Real-time updates (WebSocket) | GraphQL Subscriptions |
| Webhooks / callbacks | REST (POST) |
| Latency < 5ms between services | gRPC |
| You control only one side (consumer) | REST |
| Both sides are your code | gRPC |

---

## Interview questions

**"Design the API for a support ticketing system"**
Answer: REST for external developer integrations (/api/v1/tickets), GraphQL for the dashboard (complex multi-entity queries), gRPC for internal microservice calls. Walk through the ticket resource design with HTTP verbs, pagination strategy (cursor-based), versioning, and auth (JWT).

**"What is the N+1 problem in GraphQL?"**
For N parent objects, a naive resolver makes N child queries. 100 tickets × 1 agent lookup = 101 DB queries. Fix with DataLoader: batches all child lookups within the same event loop tick into a single `SELECT ... WHERE id IN (...)`.

**"When would you NOT use GraphQL?"**
- Simple APIs with few entity types — REST is simpler
- Public APIs where CDN caching matters (REST has better HTTP cache semantics)
- Teams new to it (schema design and DataLoader have a learning curve)

**"What is idempotency and why does it matter?"**
Same request, same result, regardless of how many times it's called. Critical for POST because clients retry on network failures. Implement: client sends a unique UUID header, server stores result keyed to that UUID, returns cached result on retry.

**"What's the difference between authentication and authorization?"**
Auth**entication** = who are you? (JWT, OAuth2 token)
Auth**orization** = what can you do? (RBAC, tenant isolation checks)
Both happen in the API gateway before the request reaches any service.

---

## Key takeaways

1. REST = external APIs. Universal, simple, HTTP-native, great CDN caching.
2. GraphQL = complex UI queries. One request for many entities. Watch for N+1 — always DataLoader.
3. gRPC = internal services. Binary, fast, typed. Never expose to browsers.
4. Always version APIs from day one. Always use cursor pagination. Always add idempotency keys on write endpoints.

---

*Next: [Day 2 — Load Balancing + Rate Limiting](../day-02-load-balancing/README.md)*
