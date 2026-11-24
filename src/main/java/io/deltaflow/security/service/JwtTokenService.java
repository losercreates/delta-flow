package io.deltaflow.security.service;

import io.deltaflow.security.domain.AuthenticationType;
import io.deltaflow.security.domain.SecurityPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class JwtTokenService {

    private final SecretKey secretKey;
    private final long expirationSeconds;
    private final long clockSkewSeconds;

    public JwtTokenService(
            @Value("${deltaflow.security.jwt.secret:deltaflow-super-secret-hmac-sha256-signing-key-for-financial-grade-tokens-2026-deltaflow}") String secret,
            @Value("${deltaflow.security.jwt.expiration-seconds:86400}") long expirationSeconds,
            @Value("${deltaflow.security.jwt.clock-skew-seconds:60}") long clockSkewSeconds
    ) {
        // Ensure secret key has at least 256 bits (32 bytes)
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            this.secretKey = Keys.hmacShaKeyFor(padded);
        } else {
            this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        }
        this.expirationSeconds = expirationSeconds;
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public String generateToken(String principalId, String tenantId, Set<String> roles) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(principalId)
                .claim("tenantId", tenantId != null ? tenantId : "default")
                .claim("roles", roles != null ? new ArrayList<>(roles) : List.of("ROLE_VIEWER"))
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationSeconds * 1000))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseAndValidateToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .clockSkewSeconds(clockSkewSeconds)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public SecurityPrincipal extractPrincipal(String token) {
        Claims claims = parseAndValidateToken(token);
        String sub = claims.getSubject();
        String tenantId = claims.get("tenantId", String.class);
        if (tenantId == null) {
            tenantId = "default";
        }

        Set<String> roles = new HashSet<>();
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    roles.add(item.toString());
                }
            }
        }

        return new SecurityPrincipal(sub, tenantId, roles, AuthenticationType.JWT_BEARER);
    }
}
