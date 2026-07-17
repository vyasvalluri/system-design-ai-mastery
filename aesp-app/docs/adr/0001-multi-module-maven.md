# ADR 0001: Multi-Module Maven Project, Not a Single Monolithic Module

**Status**: Accepted
**Date**: 2026-07-17
**Curriculum context**: Day 6 (Microservices Patterns), Day 16 (synthesis)

## Context

AESP needs to demonstrate genuine service boundaries (Day 6) - independent
deployability, independent scaling, and a real API Gateway (Day 1-2) sitting in
front of more than one backend - while staying buildable and runnable by one
person without a Kubernetes cluster.

## Decision

Use a single Maven **reactor** (`aesp-app` parent POM, packaging `pom`) containing
multiple independently-buildable and independently-runnable modules:

- `aesp-events` - shared event contract library (jar), depended on by producers
  and consumers so schemas can't silently diverge
- `aesp-ticket-service` - the core write-path service (Spring Boot app)
- `aesp-gateway` - edge routing + rate limiting (Spring Boot app)

Each service module produces its own runnable jar and has its own
`application.yml`, so `aesp-ticket-service` and `aesp-gateway` really are two
separate deployables that happen to share a build file and a Git repo - not one
process with internal package boundaries pretending to be microservices.

## Alternatives Considered

- **Fully separate repos per service.** More realistic for a large org, but
  adds cross-repo versioning overhead that doesn't pay for itself at this
  project's scale, and makes it harder to review the whole system in one place
  for curriculum/portfolio purposes.
- **Single Spring Boot module, package-by-feature.** Simpler to run, but
  collapses exactly the boundary (independent deployability, independent
  gateway routing) that Day 1-2 and Day 6 are meant to demonstrate - it would
  be a monolith with well-organized packages, not microservices.

## Consequences

- Running the full system requires starting `aesp-ticket-service` and
  `aesp-gateway` as separate processes (plus `docker-compose up` for infra) -
  more moving parts than a single `mvn spring-boot:run`, by design.
- `aesp-events` versioning discipline matters: a breaking change to
  `TicketCreatedEvent` now needs the same "expand, migrate consumers, contract"
  approach a real schema-registry-backed event contract would need, even at
  this small scale - which is itself a useful thing to have to reason about.
