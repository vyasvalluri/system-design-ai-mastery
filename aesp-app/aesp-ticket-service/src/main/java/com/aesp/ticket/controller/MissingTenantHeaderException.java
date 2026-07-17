package com.aesp.ticket.controller;

public class MissingTenantHeaderException extends RuntimeException {
    public MissingTenantHeaderException() {
        super("X-Tenant-Id header is required");
    }
}
