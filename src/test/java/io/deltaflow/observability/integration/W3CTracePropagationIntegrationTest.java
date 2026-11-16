package io.deltaflow.observability.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.observability.filter.W3CTraceContextFilter;
import io.deltaflow.observability.tracing.DeltaFlowTracer;
import io.deltaflow.observability.tracing.TraceContextHolder;
import io.deltaflow.observability.tracing.TraceContextMetadata;
import io.deltaflow.outbox.domain.OutboxEvent;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import io.deltaflow.outbox.service.OutboxService;
import io.deltaflow.saga.builder.SagaBuilder;
import io.deltaflow.saga.builder.SagaDefinition;
import io.deltaflow.saga.service.SagaCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class W3CTracePropagationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private W3CTraceContextFilter traceContextFilter;

    @Autowired
    private SagaCoordinator sagaCoordinator;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private DeltaFlowTracer deltaFlowTracer;

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    @DisplayName("Should extract incoming W3C traceparent and inject response header")
    void shouldExtractTraceparentHeader() throws Exception {
        String incomingTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(W3CTraceContextFilter.TRACEPARENT_HEADER, incomingTraceparent);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<TraceContextMetadata> capturedInChain = new AtomicReference<>();
        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                capturedInChain.set(TraceContextHolder.get());
            }
        };

        traceContextFilter.doFilter(request, response, filterChain);

        // Verify captured context during chain execution
        TraceContextMetadata captured = capturedInChain.get();
        assertThat(captured).isNotNull();
        assertThat(captured.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(captured.parentSpanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(captured.traceFlags()).isEqualTo("01");

        // Verify response header contains active traceparent
        String responseTraceparent = response.getHeader(W3CTraceContextFilter.TRACEPARENT_HEADER);
        assertThat(responseTraceparent).isNotNull();
        assertThat(responseTraceparent).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-");

        // Verify context cleared after filter completion
        assertThat(TraceContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("Should generate new compliant W3C traceparent when header is missing")
    void shouldGenerateTraceparentWhenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<TraceContextMetadata> capturedInChain = new AtomicReference<>();
        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                capturedInChain.set(TraceContextHolder.get());
            }
        };

        traceContextFilter.doFilter(request, response, filterChain);

        TraceContextMetadata captured = capturedInChain.get();
        assertThat(captured).isNotNull();
        assertThat(captured.traceId()).hasSize(32);
        assertThat(captured.spanId()).hasSize(16);
        assertThat(captured.traceFlags()).isEqualTo("01");

        String responseTraceparent = response.getHeader(W3CTraceContextFilter.TRACEPARENT_HEADER);
        assertThat(responseTraceparent).isEqualTo(captured.traceparent());
    }

    @Test
    @DisplayName("Should propagate W3C traceparent into virtual thread parallel saga branches")
    void shouldPropagateTraceContextAcrossVirtualThreads() {
        String testTraceId = "11223344556677889900aabbccddeeff";
        String testSpanId = "1234567890abcdef";
        TraceContextMetadata parentContext = TraceContextMetadata.create(testTraceId, testSpanId, null, "01");
        TraceContextHolder.set(parentContext);

        AtomicReference<String> branch1TraceId = new AtomicReference<>();
        AtomicReference<String> branch2TraceId = new AtomicReference<>();

        SagaDefinition<Map<String, Object>, Map<String, Object>> definition =
                SagaBuilder.<Map<String, Object>, Map<String, Object>>named("trace-virtual-thread-saga")
                        .parallelStep("parallel-verification", p -> p
                                .branch("branch-1", (ctx, input) -> {
                                    branch1TraceId.set(TraceContextHolder.getTraceId());
                                    return Map.of("b1", "ok");
                                }, (ctx, input) -> {})
                                .branch("branch-2", (ctx, input) -> {
                                    branch2TraceId.set(TraceContextHolder.getTraceId());
                                    return Map.of("b2", "ok");
                                }, (ctx, input) -> {})
                        )
                        .onCompletion((ctx, input) -> Map.of("result", "success"))
                        .build();

        UUID sagaId = UUID.randomUUID();
        Map<String, Object> result = sagaCoordinator.executeSaga(
                definition,
                sagaId,
                "tenant-trace",
                "key-trace-" + UUID.randomUUID(),
                Map.of("amount", 100)
        );

        assertThat(result).isNotNull();
        assertThat(result.get("result")).isEqualTo("success");

        // Verify both virtual threads observed the parent trace ID
        assertThat(branch1TraceId.get()).isEqualTo(testTraceId);
        assertThat(branch2TraceId.get()).isEqualTo(testTraceId);
    }

    @Test
    @DisplayName("Should inject active W3C traceparent into OutboxEvent payload and metadata")
    void shouldInjectTraceparentIntoOutboxEvent() {
        String testTraceId = "abcdefabcdefabcdefabcdefabcdef01";
        String testSpanId = "fedcba9876543210";
        TraceContextMetadata metadata = TraceContextMetadata.create(testTraceId, testSpanId, null, "01");
        TraceContextHolder.set(metadata);

        String aggregateId = "order-" + UUID.randomUUID();
        String jsonPayload = "{\"orderId\":\"" + aggregateId + "\",\"amount\":250.00}";

        OutboxEvent event = outboxService.recordEvent(
                "ORDER",
                aggregateId,
                "ORDER_CREATED",
                jsonPayload,
                "tenant-audit",
                "idemp-" + UUID.randomUUID()
        );

        assertThat(event).isNotNull();
        OutboxEvent persisted = outboxEventRepository.findById(event.getEventId()).orElseThrow();

        // Verify traceparent injected into JSON payload
        assertThat(persisted.getPayload()).contains(metadata.traceparent());
        assertThat(persisted.getPayload()).contains(testTraceId);
    }
}
