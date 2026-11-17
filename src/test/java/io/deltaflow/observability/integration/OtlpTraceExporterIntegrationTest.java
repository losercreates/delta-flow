package io.deltaflow.observability.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.config.OpenTelemetryConfig;
import io.deltaflow.observability.tracing.DeltaFlowTracer;
import io.deltaflow.observability.tracing.TraceContextHolder;
import io.deltaflow.observability.tracing.TraceContextMetadata;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("OpenTelemetry OTLP Trace Exporter Integration Test")
class OtlpTraceExporterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DeltaFlowTracer deltaFlowTracer;

    @Autowired
    private OpenTelemetry openTelemetry;

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    @DisplayName("Should successfully initialize OpenTelemetry and DeltaFlowTracer beans")
    void shouldInitializeOpenTelemetryBean() {
        assertThat(openTelemetry).isNotNull();
        assertThat(deltaFlowTracer).isNotNull();
    }

    @Test
    @DisplayName("Should create, tag, and end spans gracefully even when remote collector is offline")
    void shouldCreateAndEndSpansGracefully() {
        UUID sagaId = UUID.randomUUID();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        TraceContextHolder.set(TraceContextMetadata.create(traceId, "00f067aa0ba902b7", null, "01"));

        assertThatCode(() -> {
            Span span = deltaFlowTracer.startStepSpan(sagaId, 0, "debit-account-step");
            assertThat(span).isNotNull();
            assertThat(span.getSpanContext().isValid()).isTrue();

            deltaFlowTracer.recordStepSuccess(span);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should record step failure with exception details without blocking calling thread")
    void shouldRecordStepFailureWithoutBlocking() {
        UUID sagaId = UUID.randomUUID();
        Span span = deltaFlowTracer.startStepSpan(sagaId, 1, "credit-account-step");

        assertThatCode(() -> {
            deltaFlowTracer.recordStepFailure(span, new RuntimeException("Simulated outbound service failure"));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should record compensation span with cause attribute cleanly")
    void shouldRecordCompensationSpan() {
        UUID sagaId = UUID.randomUUID();
        assertThatCode(() -> {
            deltaFlowTracer.recordCompensationSpan(sagaId, 0, "debit-account-step", "Downstream participant timeout");
        }).doesNotThrowAnyException();
    }
}
