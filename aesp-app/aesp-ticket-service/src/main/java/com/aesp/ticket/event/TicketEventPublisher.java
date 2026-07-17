package com.aesp.ticket.event;

import com.aesp.events.TicketCreatedEvent;
import com.aesp.events.TicketStatusChangedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Curriculum: Day 5, Day 8 Deep Dive 1. Publishing is fire-and-forget from the
 * caller's perspective on the happy path - TicketService does NOT wait on this
 * before returning 202 to the client. If Kafka itself is down, that failure is
 * handled here (logged + surfaced to metrics), not allowed to fail the HTTP
 * request that already durably wrote the ticket to Postgres.
 */
@Component
public class TicketEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String ticketCreatedTopic;
    private final String ticketStatusChangedTopic;

    public TicketEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${aesp.topics.ticket-created}") String ticketCreatedTopic,
            @Value("${aesp.topics.ticket-status-changed}") String ticketStatusChangedTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.ticketCreatedTopic = ticketCreatedTopic;
        this.ticketStatusChangedTopic = ticketStatusChangedTopic;
    }

    public void publishCreated(TicketCreatedEvent event) {
        // Keyed by tenantId so all events for a tenant land on the same partition,
        // preserving per-tenant ordering for any consumer that needs it (e.g. an
        // analytics worker building a running SLA counter).
        kafkaTemplate.send(ticketCreatedTopic, event.tenantId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // In production: increment a metric + alert on sustained failure.
                        // Never rethrow - ingestion already succeeded and must not roll back.
                        System.err.println("Failed to publish TicketCreatedEvent: " + ex.getMessage());
                    }
                });
    }

    public void publishStatusChanged(TicketStatusChangedEvent event) {
        kafkaTemplate.send(ticketStatusChangedTopic, event.tenantId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        System.err.println("Failed to publish TicketStatusChangedEvent: " + ex.getMessage());
                    }
                });
    }
}
