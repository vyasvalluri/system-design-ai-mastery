package com.aesp.ticket.dto;

import com.aesp.ticket.domain.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Curriculum: Day 1 (APIs). Validation happens at the edge (this DTO), never
 * relying on the entity/DB layer to reject bad input - by the time it would hit
 * the DB, you've already spent a round trip you didn't need to.
 */
public record CreateTicketRequest(

        @NotBlank
        @Size(max = 500)
        String subject,

        @NotBlank
        String body,

        @NotNull
        Channel channel,

        // Set by the ingesting channel adapter (e.g. the email connector uses the
        // provider's message ID) - lets the dedup index in V1__create_tickets_table.sql
        // do its job against retried deliveries. Optional for channels without a
        // natural dedup key.
        String idempotencyKey
) {}
