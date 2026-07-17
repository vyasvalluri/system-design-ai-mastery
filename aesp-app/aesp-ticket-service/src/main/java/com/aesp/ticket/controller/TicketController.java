package com.aesp.ticket.controller;

import com.aesp.ticket.dto.CreateTicketRequest;
import com.aesp.ticket.dto.TicketResponse;
import com.aesp.ticket.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * Curriculum: Day 1 (REST API design - versioned path, tenant via header not path,
 * consistent DTOs in/out). Tenant resolution here is a simple required header for
 * the reference implementation; in production this comes from a validated JWT
 * claim set by the gateway (see aesp-gateway), never trusted raw from the client.
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping
    public ResponseEntity<TicketResponse> createTicket(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody CreateTicketRequest request) {

        TicketResponse created = ticketService.createTicket(tenantId, request);

        // 202 Accepted, not 201 Created: the ticket row is durably written, but
        // AI triage/enrichment consuming the TicketCreatedEvent is async and not
        // guaranteed complete yet - this is the Day 8 prompt's core API decision.
        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v1/tickets/" + created.id()))
                .body(created);
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketResponse> getTicket(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable UUID ticketId) {
        return ResponseEntity.ok(ticketService.getTicket(tenantId, ticketId));
    }

    @GetMapping
    public ResponseEntity<Page<TicketResponse>> listTickets(
            @RequestHeader("X-Tenant-Id") String tenantId,
            Pageable pageable) {
        return ResponseEntity.ok(ticketService.listTickets(tenantId, pageable));
    }

    @PostMapping("/{ticketId}/assign")
    public ResponseEntity<TicketResponse> assignTicket(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable UUID ticketId,
            @RequestParam String agentId) {
        return ResponseEntity.ok(ticketService.assignTicket(tenantId, ticketId, agentId));
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<Void> handleMissingHeader() {
        throw new MissingTenantHeaderException();
    }
}
