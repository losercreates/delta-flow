package io.deltaflow.idempotency.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class PayloadHasherTest {

    private final PayloadHasher hasher = new PayloadHasher();

    @Test
    @DisplayName("Hash of identical bytes is deterministic and lowercase hex")
    void testDeterministicHashing() {
        byte[] payload1 = "{\"amount\": 100}".getBytes(StandardCharsets.UTF_8);
        byte[] payload2 = "{\"amount\": 100}".getBytes(StandardCharsets.UTF_8);

        String hash1 = hasher.computeHash(payload1, null);
        String hash2 = hasher.computeHash(payload2, null);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
        assertThat(hash1).matches("^[a-f0-9]{64}$");
    }

    @Test
    @DisplayName("Different payload produces distinct hash")
    void testDifferentPayloads() {
        byte[] payload1 = "{\"amount\": 100}".getBytes(StandardCharsets.UTF_8);
        byte[] payload2 = "{\"amount\": 200}".getBytes(StandardCharsets.UTF_8);

        String hash1 = hasher.computeHash(payload1, null);
        String hash2 = hasher.computeHash(payload2, null);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("Empty payload produces consistent SHA-256 empty digest")
    void testEmptyPayload() {
        String hash = hasher.computeHash(new byte[0], "");
        assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}

