package io.deltaflow.security.domain;

import java.util.Set;

public record SecurityPrincipal(
    String principalId,
    String tenantId,
    Set<String> roles,
    AuthenticationType authType
) {
    public boolean hasRole(String role) {
        if (roles == null) {
            return false;
        }
        return roles.contains(role) || roles.contains("ROLE_" + role);
    }
}
