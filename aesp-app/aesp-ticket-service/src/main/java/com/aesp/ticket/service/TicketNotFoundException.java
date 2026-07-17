package com.aesp.ticket.service;

import java.util.UUID;

public class TicketNotFoundException extends RuntimeException {
    public TicketNotFoundException(UUID ticketId) {
        super("Ticket not found: " + ticketId);
    }
}
