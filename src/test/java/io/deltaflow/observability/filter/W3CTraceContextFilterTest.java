package io.deltaflow.observability.filter;

import io.deltaflow.observability.tracing.TraceContextHolder;
import io.deltaflow.observability.tracing.TraceContextMetadata;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class W3CTraceContextFilterTest {

    private final W3CTraceContextFilter filter = new W3CTraceContextFilter();

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    @DisplayName("Should extract incoming traceparent header and inject response header")
    void shouldExtractIncomingTraceparent() throws ServletException, IOException {
        String incoming = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(W3CTraceContextFilter.TRACEPARENT_HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<TraceContextMetadata> captured = new AtomicReference<>();
        FilterChain chain = (req, res) -> captured.set(TraceContextHolder.get());

        filter.doFilter(request, response, chain);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(captured.get().parentSpanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(captured.get().traceFlags()).isEqualTo("01");

        String respHeader = response.getHeader(W3CTraceContextFilter.TRACEPARENT_HEADER);
        assertThat(respHeader).isNotNull();
        assertThat(respHeader).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-");

        // Verify threadlocal cleanup
        assertThat(TraceContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("Should generate new compliant W3C traceparent when header is missing or malformed")
    void shouldGenerateWhenMissingOrMalformed() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(W3CTraceContextFilter.TRACEPARENT_HEADER, "invalid-header");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<TraceContextMetadata> captured = new AtomicReference<>();
        FilterChain chain = (req, res) -> captured.set(TraceContextHolder.get());

        filter.doFilter(request, response, chain);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().traceId()).hasSize(32);
        assertThat(captured.get().spanId()).hasSize(16);
        assertThat(captured.get().traceparent()).startsWith("00-");
    }
}
