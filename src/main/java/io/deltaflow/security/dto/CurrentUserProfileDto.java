package io.deltaflow.security.dto;

import java.util.Set;

public record CurrentUserProfileDto(
    String principalId,
    String tenantId,
    Set<String> roles,
    String authType
) {}
