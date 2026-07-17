# ADR 0002: Simplified In-Service Saga Step for Ticket Assignment (Not a Full Orchestrator)

**Status**: Accepted (with a documented follow-up)
**Date**: 2026-07-17
**Curriculum context**: Day 6 (Saga Pattern, CQRS), Day 8 Deep Dive 1

## Context

Day 6 covers the Saga pattern for coordinating a multi-step, multi-service
transaction (e.g., check agent availability -> assign ticket -> notify agent),
where a full 2PC/distributed transaction isn't viable across service
boundaries. A textbook Saga implementation needs at least two independent
services and an orchestrator (or choreography via events) coordinating them,
with compensating actions if a later step fails.

## Decision

`TicketService.assignTicket()` currently implements this as a **single
transactional method inside one service**, not a true multi-service Saga - it
checks the ticket's current state and writes the assignment in one DB
transaction, then publishes `TicketStatusChangedEvent`.

This is a deliberate simplification for the reference implementation's current
scope, not an oversight: introducing a real Agent Availability service (with
its own datastore) purely to demonstrate the Saga pattern would add a service
whose only job is teaching the pattern, rather than doing real work for AESP.

## Alternatives Considered

- **Build a real Agent Availability microservice now**, with `assignTicket`
  becoming a true two-step Saga (reserve availability -> confirm assignment,
  with a compensating "release availability" step on failure). This is the
  architecturally "correct" demonstration of Day 6's pattern, deferred rather
  than rejected - see Consequences.
- **Orchestrator vs. choreography** for that future Saga: leaning toward a
  lightweight orchestrator inside `aesp-ticket-service` (rather than pure
  event choreography across services) because the assignment flow has a small,
  fixed number of steps and a clear owner - choreography's benefit (loose
  coupling) matters more for flows with many independent subscribers, which
  this isn't yet.

## Consequences

- The current code correctly prevents double-assignment (Day 7's CP framing
  is real, enforced by the DB transaction) but does **not** yet demonstrate a
  cross-service compensating action, which is the part of Saga that's hardest
  to get right and most worth practicing.
- **Follow-up work**, tracked for when a second real service is warranted:
  extract agent availability into `aesp-agent-service`, and rewrite
  `assignTicket` as a genuine two-step Saga with a compensating
  "release-availability" event on assignment failure. This ADR should be
  revisited (status changed to Superseded) at that point.
