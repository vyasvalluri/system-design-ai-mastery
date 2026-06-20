# Day 1 — APIs: REST vs GraphQL vs gRPC

> **AESP Context:** The API Gateway layer serves three different client types — each with different needs. Choosing the right protocol for each is the first real design decision in AESP.

---

## The Core Question

When a client (web app, mobile app, external developer) needs to talk to your backend, what protocol do you use? There are three main answers in 2024: REST, GraphQL, and gRPC. Each solves a different problem.

---

## REST (Representational State Transfer)

REST is the default. Resources are nouns, HTTP verbs are actions.

```
POST   /api/v1/tickets          → create a ticket
GET    /api/v1/tickets/:id      → get a ticket
PATCH  /api/v1/tickets/:id      → update a ticket
DELETE /api/v1/tickets/:id      → delete a ticket
GET    /api/v1/tickets?status=open&page=2  → list with filters
```

### AESP REST API design

```javascript
// Ticket Service — Express.js
// POST /api/v1/tickets
router.post('/tickets', authenticate, rateLimit, async (req, res) => {
  const { subject, description, priority, tenantId } = req.body;

  // Validate
  if (!subject || !description) {
    return res.status(400).json({ error: 'subject and description required' });
  }

  // Create ticket
  const ticket = await ticketService.create({
    subject,
    description,
    priority: priority || 'medium',
    tenantId,
    status: 'open',
    createdAt: new Date().toISOString(),
    idempotencyKey: req.headers['idempotency-key']  // prevent duplicates
  });

  // Publish event to Kafka
  await kafka.publish('ticket.created', { ticketId: ticket.id, tenantId });

  return res.status(201).json({ data: ticket });
});
```

### REST best practices
- **Versioning:** Always version your API (`/v1/`, `/v2/`). Never break existing clients.
- **Idempotency keys:** For POST requests, let clients send a unique key so retries don't create duplicates.
- **Pagination:** Cursor-based over offset — `?cursor=abc123&limit=20` instead of `?page=2&limit=20` (offset breaks when rows are inserted mid-pagination).
- **HTTP status codes:** 200 OK, 201 Created, 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 409 Conflict, 429 Too Many Requests, 500 Server Error.

---

## GraphQL

GraphQL solves **over-fetching** and **under-fetching**. The client asks for exactly what it needs — nothing more, nothing less.

```graphql
# Client asks for exactly these fields — no more
query GetTicketWithUser {
  ticket(id: "t_123") {
    id
    subject
    status
    priority
    assignedAgent {
      name
      email
    }
    comments(last: 3) {
      text
      createdAt
    }
  }
}
```

### AESP GraphQL schema (partial)

```graphql
type Ticket {
  id: ID!
  subject: String!
  description: String!
  status: TicketStatus!
  priority: Priority!
  tenant: Tenant!
  assignedAgent: Agent
  comments: [Comment!]!
  aiResolution: AIResolution
  createdAt: DateTime!
  updatedAt: DateTime!
}

enum TicketStatus {
  OPEN
  IN_PROGRESS
  RESOLVED
  ESCALATED
}

type Query {
  ticket(id: ID!): Ticket
  tickets(filter: TicketFilter, pagination: PaginationInput): TicketConnection!
}

type Mutation {
  createTicket(input: CreateTicketInput!): Ticket!
  updateTicketStatus(id: ID!, status: TicketStatus!): Ticket!
}

type Subscription {
  ticketUpdated(id: ID!): Ticket!  # real-time updates via WebSocket
}
```

**When to use GraphQL in AESP:** The web dashboard — it shows ticket details, agent info, AI resolution summary, and comments all in one view. Without GraphQL, that's 4 REST calls. With GraphQL, it's 1.

**Watch out for:** N+1 problem — for a list of 100 tickets, GraphQL might make 100 separate DB calls to fetch each ticket's `assignedAgent`. Fix with **DataLoader** (batches + caches DB calls).

---

## gRPC (Google Remote Procedure Call)

gRPC uses **Protocol Buffers** (binary format) instead of JSON (text). It's ~5–10x faster and strongly typed. Used for internal service-to-service communication.

```protobuf
// ticket.proto
syntax = "proto3";

service TicketService {
  rpc CreateTicket(CreateTicketRequest) returns (TicketResponse);
  rpc GetTicket(GetTicketRequest) returns (TicketResponse);
  rpc StreamTicketUpdates(TicketStreamRequest) returns (stream TicketEvent);
}

message CreateTicketRequest {
  string subject = 1;
  string description = 2;
  string tenant_id = 3;
  Priority priority = 4;
}

message TicketResponse {
  string id = 1;
  string subject = 2;
  TicketStatus status = 3;
  int64 created_at = 4;
}
```

**When to use gRPC in AESP:** Internal calls between microservices. The AI Orchestration Layer calling the Ticket Service to update ticket status after resolution. Low latency, strongly typed contract, no JSON parsing overhead.

---

## Decision Matrix — which to use where

| Client / Use Case | Protocol | Why |
|---|---|---|
| External developers / third-party integrations | REST | Universal, easy to use, well understood |
| Web dashboard (complex queries, multiple entities) | GraphQL | Flexible queries, reduces round trips |
| Mobile app (bandwidth sensitive) | GraphQL | Request only needed fields |
| Internal microservice calls | gRPC | Low latency, binary protocol, strong types |
| Real-time ticket updates | WebSocket / gRPC streaming | Bidirectional, persistent connection |
| Webhook callbacks | REST (POST) | Simple, stateless, widely supported |

### AESP API Gateway routing

```
Client Request
      │
      ▼
API Gateway
      │
      ├─ /api/v1/*           → REST routes (external devs, email ingestion)
      ├─ /graphql             → GraphQL endpoint (web + mobile)
      └─ internal gRPC mesh  → service-to-service (Istio, not exposed externally)
```

---

## Interview Questions

**Q: Design the API for a ticketing system like Zendesk.**

Structure your answer:
1. Identify clients (web, mobile, external API, email)
2. Choose protocols per client (REST + GraphQL + gRPC internal)
3. Define key resources: Ticket, User, Agent, Comment, Attachment
4. Design endpoints with proper HTTP verbs and status codes
5. Discuss versioning, pagination, auth, rate limiting

**Q: What is the N+1 problem in GraphQL and how do you fix it?**

If you have a list of N tickets and each ticket has an `assignedAgent` field, a naive resolver will make N separate DB queries for agents. Fix: use DataLoader to batch all agent IDs into a single `SELECT * FROM agents WHERE id IN (...)` query.

**Q: When would you choose gRPC over REST?**

Internal service communication where latency matters, you control both sides, and you want a typed contract. REST for external APIs because gRPC requires client library generation and isn't browser-native.

**Q: How do you handle API versioning?**

URL versioning (`/v1/`, `/v2/`) is simplest and most explicit. Header versioning (`Accept: application/vnd.myapp.v2+json`) is cleaner but harder to cache. Never make breaking changes to an existing version — deprecate and sunset old versions with advance notice.

---

## Key Takeaways

1. **REST** = default for external APIs. Simple, universal, stateless.
2. **GraphQL** = when clients need flexible queries and you want to avoid over/under-fetching.
3. **gRPC** = internal service mesh. Fast, typed, binary.
4. **Idempotency** = always handle retries safely on POST endpoints.
5. **Cursor pagination** = better than offset for large, changing datasets.

---

*Next: [Day 2 — Load Balancing + Rate Limiting](../day-02-load-balancing/README.md)*
