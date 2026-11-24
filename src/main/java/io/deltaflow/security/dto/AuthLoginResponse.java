package io.deltaflow.security.dto;

import java.util.Set;

public record AuthLoginResponse(
    String token,
    String tokenType,
    long expiresInSeconds,
    String tenantId,
    Set<String> roles
) {}
