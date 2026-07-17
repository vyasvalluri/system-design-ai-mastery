package com.aesp.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Curriculum: Day 2 (Rate Limiting). Rate limiting is keyed by tenant, not by
 * client IP - the failure mode this prevents is one noisy/misbehaving tenant
 * (e.g. a runaway integration retry loop) starving every other tenant's traffic
 * on shared infrastructure. Falls back to "unknown" only for requests that
 * somehow arrive without the header, which the ticket-service's own validation
 * will reject anyway - the gateway's rate limiter isn't the source of truth for
 * tenant validity, just fairness.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver tenantKeyResolver() {
        return exchange -> {
            String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
            return Mono.just(tenantId != null ? tenantId : "unknown");
        };
    }
}
