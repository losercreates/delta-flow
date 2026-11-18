package io.deltaflow.saga.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class WebhookSecurityService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecurityService.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    @Value("${deltaflow.webhook.secret:deltaflow-webhook-secret-key-default}")
    private String defaultSecret;

    @Value("${deltaflow.webhook.timestamp-tolerance-seconds:300}")
    private long timestampToleranceSeconds;

    public WebhookSecurityService() {
    }

    public WebhookSecurityService(String defaultSecret, long timestampToleranceSeconds) {
        this.defaultSecret = defaultSecret;
        this.timestampToleranceSeconds = timestampToleranceSeconds;
    }

    public boolean verifyTimestamp(long timestamp) {
        return verifyTimestamp(timestamp, this.timestampToleranceSeconds);
    }

    public boolean verifyTimestamp(long timestamp, long toleranceSeconds) {
        long currentMillis = Instant.now().toEpochMilli();
        long diff = Math.abs(currentMillis - timestamp);
        long maxDiff = toleranceSeconds * 1000L;
        boolean valid = diff <= maxDiff;
        if (!valid) {
            log.warn("Webhook timestamp drift rejected: current={}, received={}, diffMs={}, maxDiffMs={}",
                    currentMillis, timestamp, diff, maxDiff);
        }
        return valid;
    }

    public String computeHmacSha256(String data, String secret) {
        if (data == null || secret == null) {
            throw new IllegalArgumentException("Data and secret must not be null");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", e);
        }
    }

    public boolean verifySignature(String rawBody, long timestamp, String signatureHeader) {
        return verifySignature(rawBody, timestamp, signatureHeader, this.defaultSecret);
    }

    public boolean verifySignature(String rawBody, long timestamp, String signatureHeader, String secret) {
        if (signatureHeader == null || signatureHeader.isBlank() || secret == null || secret.isBlank()) {
            log.warn("Webhook signature verification failed: empty signature or secret");
            return false;
        }

        String cleanSig = signatureHeader.trim();
        if (cleanSig.startsWith("sha256=")) {
            cleanSig = cleanSig.substring("sha256=".length()).trim();
        } else if (cleanSig.startsWith("v1=")) {
            cleanSig = cleanSig.substring("v1=".length()).trim();
        }

        String payloadToSign = timestamp + "." + (rawBody != null ? rawBody : "");
        String expectedSignature = computeHmacSha256(payloadToSign, secret);

        // Constant-time comparison to prevent timing side-channel attacks
        return MessageDigest.isEqual(
                cleanSig.toLowerCase().getBytes(StandardCharsets.UTF_8),
                expectedSignature.toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }

    public String getDefaultSecret() {
        return defaultSecret;
    }

    public long getTimestampToleranceSeconds() {
        return timestampToleranceSeconds;
    }
}
