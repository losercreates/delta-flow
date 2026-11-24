package io.deltaflow.security.dto;

public record TenantRateLimitStatusDto(
    String tenantId,
    long limit,
    long remaining,
    long resetMs,
    boolean rateLimited
) {}
