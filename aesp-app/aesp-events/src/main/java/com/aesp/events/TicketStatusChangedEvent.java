package com.aesp.events;

import java.time.Instant;

/**
 * Published whenever a ticket's status/assignment changes (created -> assigned -> resolved,
 * etc). This is the event the Analytics Worker's CQRS read-model consumer subscribes to -
 * the write side (Ticket entity in aesp-ticket-service) stays normalized and transactional,
 * while any denormalized "tickets by status per day" or "SLA breach rate" read model is
 * built entirely from this event stream, independently and eventually-consistently.
 *
 * Curriculum: Day 6 (CQRS), Day 7 (CAP - this stream is explicitly the AP side of the
 * system; the write side in aesp-ticket-service is CP).
 */
public record TicketStatusChangedEvent(
        String eventId,
        String ticketId,
        String tenantId,
        String previousStatus,
        String newStatus,
        String assignedAgentId, // nullable - null until routed
        Instant occurredAt
) {
    public static TicketStatusChangedEvent of(String ticketId, String tenantId,
                                                String previousStatus, String newStatus,
                                                String assignedAgentId) {
        return new TicketStatusChangedEvent(
                java.util.UUID.randomUUID().toString(),
                ticketId,
                tenantId,
                previousStatus,
                newStatus,
                assignedAgentId,
                Instant.now()
        );
    }
}
