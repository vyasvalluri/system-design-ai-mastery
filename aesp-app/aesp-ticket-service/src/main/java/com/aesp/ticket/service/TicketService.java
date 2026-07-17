package com.aesp.ticket.service;

import com.aesp.events.TicketCreatedEvent;
import com.aesp.events.TicketStatusChangedEvent;
import com.aesp.ticket.domain.Ticket;
import com.aesp.ticket.dto.CreateTicketRequest;
import com.aesp.ticket.dto.TicketResponse;
import com.aesp.ticket.event.TicketEventPublisher;
import com.aesp.ticket.repository.TicketRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Curriculum home for Days 1, 3, 4, 5, 6, 7 in one class - deliberately, since in
 * the real system these concerns genuinely live together at the ticket-write
 * boundary. See method-level comments for which day each block maps to.
 */
@Service
public class TicketService {

    private final TicketRepository repository;
    private final TicketEventPublisher eventPublisher;

    public TicketService(TicketRepository repository, TicketEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Day 8 / Day 5: durable write + immediate return, event published async.
     * Day 8 bottleneck mitigation: idempotency check against retried channel
     * deliveries (e.g. an email provider retrying a webhook) happens BEFORE any
     * write, so a retry is a no-op read, not a duplicate ticket.
     */
    @Transactional
    public TicketResponse createTicket(String tenantId, CreateTicketRequest request) {
        if (request.idempotencyKey() != null) {
            var existing = repository.findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey());
            if (existing.isPresent()) {
                return TicketResponse.from(existing.get());
            }
        }

        Ticket ticket = Ticket.create(
                tenantId, request.subject(), request.body(),
                request.channel(), request.idempotencyKey());

        Ticket saved = repository.save(ticket);

        // Day 5: publish AFTER the transactional write commits conceptually - in a
        // stricter setup this would go through an outbox table + relay rather than
        // publishing inline inside the same method, to guarantee the event is never
        // lost if the process crashes between DB commit and Kafka send. Noted here
        // as the next hardening step rather than implemented, to keep this module
        // focused on the CQRS/caching/API concerns it's teaching.
        eventPublisher.publishCreated(TicketCreatedEvent.of(
                saved.getId().toString(), tenantId, saved.getSubject(),
                saved.getBody(), saved.getChannel().name(), saved.getCreatedAt()));

        return TicketResponse.from(saved);
    }

    /**
     * Day 3: cache-aside read. Cache key includes tenantId so eviction and
     * lookups never cross tenant boundaries even at the cache layer.
     */
    @Cacheable(cacheNames = "tickets", key = "#tenantId + ':' + #ticketId")
    public TicketResponse getTicket(String tenantId, UUID ticketId) {
        Ticket ticket = repository.findByIdAndTenantId(ticketId, tenantId)
                .orElseThrow(() -> new TicketNotFoundException(ticketId));
        return TicketResponse.from(ticket);
    }

    public Page<TicketResponse> listTickets(String tenantId, Pageable pageable) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                .map(TicketResponse::from);
    }

    /**
     * Day 6: simplified Saga step (check -> assign -> notify, per Day 8 Deep Dive 1's
     * Saga walkthrough). A real implementation would call an Agent Availability
     * service between "check" and "assign"; that call boundary is where a
     * compensating action would reroute to the next available agent if the chosen
     * agent went offline between the check and the assignment write. Modeled here
     * as a single-service method for the reference implementation; see
     * docs/adr/0002-saga-vs-orchestrator.md for the multi-service version.
     *
     * Day 7: this write is intentionally CP - strong consistency so two agents
     * are never assigned the same ticket, even at the cost of brief unavailability
     * under a partition.
     */
    @Transactional
    @CacheEvict(cacheNames = "tickets", key = "#tenantId + ':' + #ticketId")
    public TicketResponse assignTicket(String tenantId, UUID ticketId, String agentId) {
        Ticket ticket = repository.findByIdAndTenantId(ticketId, tenantId)
                .orElseThrow(() -> new TicketNotFoundException(ticketId));

        String previousStatus = ticket.getStatus().name();
        ticket.assignTo(agentId);
        Ticket saved = repository.save(ticket);

        // Day 6: this is the event the CQRS analytics read-model consumer builds
        // its "tickets by status" / "SLA breach rate" projections from - the
        // AP side of the system per Day 7.
        eventPublisher.publishStatusChanged(TicketStatusChangedEvent.of(
                saved.getId().toString(), tenantId, previousStatus,
                saved.getStatus().name(), agentId));

        return TicketResponse.from(saved);
    }
}
