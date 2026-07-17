package com.aesp.events;

import java.time.Instant;

/**
 * Published by aesp-ticket-service on the Kafka topic "ticket.created" the moment a ticket
 * is durably written - never blocking the synchronous ticket-creation response on it.
 *
 * Curriculum: Day 5 (Message Queues + Event-Driven Architecture), Day 8 Deep Dive 1
 * (why AI enrichment must be async, not inline).
 *
 * Consumers (AI Triage Worker, Analytics Worker, future Phase 2 RAG indexer) each read
 * this independently and can fail/retry without affecting ticket ingestion at all - that
 * decoupling is the entire point of this event existing as its own class instead of just
 * reusing the Ticket entity directly.
 */
public record TicketCreatedEvent(
        String eventId,
        String ticketId,
        String tenantId,
        String subject,
        String body,
        String channel,       // EMAIL, CHAT, WEB_FORM
        Instant createdAt,
        Instant occurredAt
) {
    public static TicketCreatedEvent of(String ticketId, String tenantId, String subject,
                                         String body, String channel, Instant createdAt) {
        return new TicketCreatedEvent(
                java.util.UUID.randomUUID().toString(),
                ticketId,
                tenantId,
                subject,
                body,
                channel,
                createdAt,
                Instant.now()
        );
    }
}
