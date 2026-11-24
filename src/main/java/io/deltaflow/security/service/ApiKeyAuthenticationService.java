package io.deltaflow.security.service;

import io.deltaflow.security.domain.AuthenticationType;
import io.deltaflow.security.domain.SecurityPrincipal;
import io.deltaflow.security.domain.TenantApiKey;
import io.deltaflow.security.repository.TenantApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class ApiKeyAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationService.class);

    private final TenantApiKeyRepository tenantApiKeyRepository;

    public ApiKeyAuthenticationService(TenantApiKeyRepository tenantApiKeyRepository) {
        this.tenantApiKeyRepository = tenantApiKeyRepository;
    }

    /**
     * Hashes raw API key using SHA-256 to match database digest.
     */
    public String hashApiKey(String rawApiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawApiKey.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }

    /**
     * Authenticates API key using constant-time byte comparison against registered tenant credentials.
     */
    public Optional<SecurityPrincipal> authenticateApiKey(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return Optional.empty();
        }

        String computedHash = hashApiKey(rawApiKey);
        Optional<TenantApiKey> recordOpt = tenantApiKeyRepository.findByHashedKeyAndEnabledTrue(computedHash);
        if (recordOpt.isEmpty()) {
            return Optional.empty();
        }

        TenantApiKey record = recordOpt.get();
        byte[] expected = computedHash.getBytes(StandardCharsets.UTF_8);
        byte[] actual = record.getHashedKey().getBytes(StandardCharsets.UTF_8);

        // Constant-time comparison to prevent timing side-channel attacks
        if (!MessageDigest.isEqual(expected, actual)) {
            log.warn("Constant-time hash mismatch for API key [{}]", record.getKeyId());
            return Optional.empty();
        }

        Set<String> roles = new HashSet<>();
        if (record.getRoles() != null && !record.getRoles().isBlank()) {
            for (String r : record.getRoles().split(",")) {
                roles.add(r.trim());
            }
        } else {
            roles.add("ROLE_SERVICE");
        }

        return Optional.of(new SecurityPrincipal(
                record.getKeyId(),
                record.getTenantId(),
                roles,
                AuthenticationType.API_KEY
        ));
    }
}
