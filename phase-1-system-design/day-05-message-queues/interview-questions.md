# Day 5 — Message Queues & Event-Driven Architecture — Interview Questions

> Extracted from `README.md` for standalone review/quizzing. See the full
> day README for the analogy, concept walkthrough, and code.


**Q: "How does Kafka guarantee message ordering?"**

> Kafka guarantees ordering only within a single partition. Messages with the same key always route to the same partition, so all events for a given `org_id` arrive in order. Across partitions there is no global ordering. If you need strict global order for a single entity — say, all state changes for one ticket — use the ticket ID as the key so all its events land in the same partition. In AESP, keying by `org_id` means all of one organization's events are ordered, which is what matters for their support workflow.

**Q: "Explain at-least-once vs exactly-once delivery."**

> At-least-once: the broker guarantees every message is delivered, but a consumer crash before committing its offset causes redelivery. Your consumer must be idempotent. Exactly-once: Kafka supports this with `enable.idempotence=true` on the producer and transactional APIs on the consumer — the message write and offset commit happen atomically. It is more complex and slightly slower. For AESP's AI processor, at-least-once with Redis deduplication is the right tradeoff — simpler, and a duplicate AI analysis that detects the idempotency key and skips processing is a no-op.

**Q: "What is the outbox pattern and why do you need it?"**

> Without outbox, publishing a Kafka event after a DB write creates a two-phase commit problem: if Kafka is down, your ticket is saved but the event is lost — downstream services never know. The outbox pattern writes the event to a DB table in the same transaction as the business record. A poller reads pending rows and publishes to Kafka, retrying until it succeeds. The DB transaction is the source of truth. Guaranteed delivery with no distributed transaction required.

**Q: "How would you handle a Kafka consumer that is falling behind?"**

> First, measure lag — the difference between the latest offset and the consumer's committed offset. If lag is growing: (1) add more consumer instances up to the partition count; (2) increase partition count if already at the consumer limit; (3) profile the consumer — if the LLM call is slow, process messages in async batches; (4) check whether the producer is producing faster than expected and add backpressure; (5) for bursty workloads, consider a two-stage pipeline — Kafka consumer writes to a work queue, a pool of workers processes independently at maximum parallelism.

**Q: "When would you choose RabbitMQ over Kafka?"**

> RabbitMQ when you need complex routing — topic exchanges, header-based routing, fanout with dead-letter re-queuing — and your volume is thousands per second, not millions. Also when you need request-reply (RPC) patterns where a service sends a message and waits for a correlated response. Kafka when you need high throughput, message replay, event sourcing, or multiple independent consumer groups reading the same stream. Kafka's operational complexity is only worth it above ~50k messages/second or when replay is a hard requirement.

---
