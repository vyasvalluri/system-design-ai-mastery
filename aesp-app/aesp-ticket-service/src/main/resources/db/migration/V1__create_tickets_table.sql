-- Curriculum: Day 4 (Databases - Indexing, Sharding, Replication)
--
-- tenant_id is the sharding key referenced throughout the curriculum (Day 4, Day 8
-- Deep Dive 2, Day 16 Deep Dive 2). This single-node schema still enforces the
-- access-pattern-aligned composite index so the query plan matches what a real
-- sharded/partitioned deployment would look like - almost every query in this
-- service is scoped to a single tenant.

CREATE TABLE tickets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       VARCHAR(64)  NOT NULL,
    subject         VARCHAR(500) NOT NULL,
    body            TEXT         NOT NULL,
    channel         VARCHAR(20)  NOT NULL,   -- EMAIL, CHAT, WEB_FORM
    status          VARCHAR(20)  NOT NULL DEFAULT 'CREATED',
    assigned_agent_id VARCHAR(64),
    idempotency_key VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Read-heavy access pattern (10:1 read:write per Day 8's capacity estimate) is
-- almost always "give me tenant X's tickets, most recent first" - this composite
-- index matches that instead of forcing a full scan + filter.
CREATE INDEX idx_tickets_tenant_created ON tickets (tenant_id, created_at DESC);

CREATE INDEX idx_tickets_tenant_status ON tickets (tenant_id, status);

-- Dedup guard for retried channel deliveries (Day 8 bottleneck: duplicate ticket
-- ingestion from email retries). Partial unique index so NULL idempotency keys
-- (e.g. tickets created directly via internal tooling) don't collide.
CREATE UNIQUE INDEX idx_tickets_tenant_idempotency
    ON tickets (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
