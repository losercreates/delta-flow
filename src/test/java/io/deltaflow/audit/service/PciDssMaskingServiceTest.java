package io.deltaflow.audit.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PciDssMaskingServiceTest {

    private final PciDssMaskingService maskingService = new PciDssMaskingService();

    @Test
    @DisplayName("Should mask 16-digit PAN leaving only last 4 digits visible")
    void shouldMaskPan() {
        String payload = "{\"sourceAccount\":\"4111222233334444\",\"amount\":150.00}";
        String masked = maskingService.mask(payload);

        assertThat(masked).doesNotContain("4111222233334444");
        assertThat(masked).contains("****-****-****-4444");
        assertThat(masked).contains("150.00");
    }

    @Test
    @DisplayName("Should redact sensitive credentials such as passwords, tokens, and cvv")
    void shouldRedactSensitiveFields() {
        String payload = "{\"password\":\"mySecret123\",\"cvv\":\"123\",\"token\":\"bearer-abc-999\",\"amount\":50}";
        String masked = maskingService.mask(payload);

        assertThat(masked).doesNotContain("mySecret123");
        assertThat(masked).doesNotContain("123");
        assertThat(masked).doesNotContain("bearer-abc-999");
        assertThat(masked).contains("[REDACTED]");
    }

    @Test
    @DisplayName("Should handle null and blank payloads gracefully")
    void shouldHandleNullOrBlank() {
        assertThat(maskingService.mask(null)).isNull();
        assertThat(maskingService.mask("")).isEqualTo("");
        assertThat(maskingService.mask("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("Should leave already masked account numbers intact")
    void shouldLeaveAlreadyMaskedAccountNumbers() {
        String payload = "{\"sourceAccount\":\"****-****-****-1002\"}";
        String masked = maskingService.mask(payload);

        assertThat(masked).isEqualTo("{\"sourceAccount\":\"****-****-****-1002\"}");
    }
}
