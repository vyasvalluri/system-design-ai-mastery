package com.aesp.ticket;

import com.aesp.ticket.domain.Channel;
import com.aesp.ticket.domain.Ticket;
import com.aesp.ticket.dto.CreateTicketRequest;
import com.aesp.ticket.dto.TicketResponse;
import com.aesp.ticket.event.TicketEventPublisher;
import com.aesp.ticket.repository.TicketRepository;
import com.aesp.ticket.service.TicketNotFoundException;
import com.aesp.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests against mocked repository/publisher - no Spring context, no
 * Testcontainers, so these run fast and without infra. Integration-level tests
 * (real Postgres/Kafka via Testcontainers) would live alongside these but are
 * intentionally out of scope for keeping this reference module runnable without
 * network access.
 */
class TicketServiceTest {

    private TicketRepository repository;
    private TicketEventPublisher eventPublisher;
    private TicketService service;

    private static final String TENANT = "tenant-acme";

    @BeforeEach
    void setUp() {
        repository = mock(TicketRepository.class);
        eventPublisher = mock(TicketEventPublisher.class);
        service = new TicketService(repository, eventPublisher);
    }

    @Test
    void createTicket_persistsAndPublishesEvent() {
        var request = new CreateTicketRequest("Can't log in", "I forgot my password", Channel.EMAIL, "msg-123");
        when(repository.findByTenantIdAndIdempotencyKey(TENANT, "msg-123")).thenReturn(Optional.empty());
        when(repository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketResponse response = service.createTicket(TENANT, request);

        assertEquals("Can't log in", response.subject());
        assertEquals("CREATED", response.status());
        verify(eventPublisher, times(1)).publishCreated(any());
    }

    @Test
    void createTicket_withDuplicateIdempotencyKey_returnsExistingWithoutPublishingAgain() {
        Ticket existing = Ticket.create(TENANT, "Existing subject", "body", Channel.EMAIL, "msg-123");
        when(repository.findByTenantIdAndIdempotencyKey(TENANT, "msg-123")).thenReturn(Optional.of(existing));

        var request = new CreateTicketRequest("Retried subject", "retried body", Channel.EMAIL, "msg-123");
        TicketResponse response = service.createTicket(TENANT, request);

        assertEquals("Existing subject", response.subject(),
                "A retried delivery with the same idempotency key must not create a second ticket");
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishCreated(any());
    }

    @Test
    void getTicket_notFound_throwsTicketNotFoundException() {
        UUID missingId = UUID.randomUUID();
        when(repository.findByIdAndTenantId(missingId, TENANT)).thenReturn(Optional.empty());

        assertThrows(TicketNotFoundException.class, () -> service.getTicket(TENANT, missingId));
    }

    @Test
    void assignTicket_updatesStatusAndPublishesStatusChangedEvent() {
        Ticket ticket = Ticket.create(TENANT, "Subject", "body", Channel.CHAT, null);
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(ticket));
        when(repository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketResponse response = service.assignTicket(TENANT, id, "agent-42");

        assertEquals("ASSIGNED", response.status());
        assertEquals("agent-42", response.assignedAgentId());
        verify(eventPublisher, times(1)).publishStatusChanged(any());
    }
}
