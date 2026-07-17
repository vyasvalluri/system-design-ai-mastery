package com.aesp.ticket.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Write-side entity for the CQRS split described in Day 6. This is the source of
 * truth for ticket state (status, assignment) - CP per Day 7's framing: a stale or
 * unavailable read here is preferable to a double-assignment.
 *
 * Analytics/dashboard reads do NOT query this entity directly at scale; they read
 * from a denormalized model built by consuming TicketStatusChangedEvent (AP side).
 */
@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    @Column(name = "assigned_agent_id", length = 64)
    private String assignedAgentId;

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Ticket() {
        // JPA
    }

    public static Ticket create(String tenantId, String subject, String body,
                                 Channel channel, String idempotencyKey) {
        Ticket t = new Ticket();
        t.tenantId = tenantId;
        t.subject = subject;
        t.body = body;
        t.channel = channel;
        t.status = TicketStatus.CREATED;
        t.idempotencyKey = idempotencyKey;
        Instant now = Instant.now();
        t.createdAt = now;
        t.updatedAt = now;
        return t;
    }

    public void assignTo(String agentId) {
        this.assignedAgentId = agentId;
        this.status = TicketStatus.ASSIGNED;
        this.updatedAt = Instant.now();
    }

    public void resolve() {
        this.status = TicketStatus.RESOLVED;
        this.updatedAt = Instant.now();
    }

    // --- getters (no setters beyond the domain methods above - state transitions
    //     are intentional actions, not free-form field mutation) ---

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Channel getChannel() { return channel; }
    public TicketStatus getStatus() { return status; }
    public String getAssignedAgentId() { return assignedAgentId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
