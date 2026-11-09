package io.deltaflow.idempotency.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class PayloadHasher {

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    public String computeHash(byte[] payload, String queryString) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (payload != null && payload.length > 0) {
                digest.update(payload);
            }
            if (queryString != null && !queryString.isBlank()) {
                digest.update(queryString.getBytes(StandardCharsets.UTF_8));
            }
            byte[] hashBytes = digest.digest();
            return HEX_FORMAT.formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

