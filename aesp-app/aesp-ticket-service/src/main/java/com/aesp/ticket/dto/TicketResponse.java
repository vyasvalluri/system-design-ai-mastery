package com.aesp.ticket.dto;

import com.aesp.ticket.domain.Ticket;
import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
        UUID id,
        String tenantId,
        String subject,
        String status,
        String assignedAgentId,
        Instant createdAt
) {
    public static TicketResponse from(Ticket t) {
        return new TicketResponse(
                t.getId(),
                t.getTenantId(),
                t.getSubject(),
                t.getStatus().name(),
                t.getAssignedAgentId(),
                t.getCreatedAt()
        );
    }
}
