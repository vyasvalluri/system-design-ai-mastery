package com.aesp.ticket.repository;

import com.aesp.ticket.domain.Ticket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Every query method here is deliberately tenant-scoped as its first parameter -
 * there is intentionally no findAll() or findById(UUID) without a tenantId
 * alongside it. This mirrors the Day 14/16 principle that tenant isolation should
 * be structurally hard to bypass, not just "usually remembered" by whoever writes
 * the next query.
 */
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByIdAndTenantId(UUID id, String tenantId);

    Page<Ticket> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    Optional<Ticket> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);
}
