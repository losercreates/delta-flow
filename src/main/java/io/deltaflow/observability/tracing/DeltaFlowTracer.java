package io.deltaflow.observability.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DeltaFlowTracer {

    private static final Logger log = LoggerFactory.getLogger(DeltaFlowTracer.class);
    private static final String INSTRUMENTATION_NAME = "io.deltaflow.saga";

    private final Tracer tracer;

    public DeltaFlowTracer() {
        this.tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    @Autowired
    public DeltaFlowTracer(Tracer tracer) {
        this.tracer = tracer != null ? tracer : GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    public Span startStepSpan(UUID sagaId, int stepIndex, String stepName) {
        TraceContextMetadata metadata = TraceContextHolder.get();
        String traceId = (metadata != null) ? metadata.traceId() : "none";

        Span span = tracer.spanBuilder("saga-step: " + stepName)
                .setAttribute("saga.id", sagaId.toString())
                .setAttribute("saga.step.index", (long) stepIndex)
                .setAttribute("saga.step.name", stepName)
                .setAttribute("w3c.traceId", traceId)
                .setAttribute("thread.name", Thread.currentThread().getName())
                .startSpan();

        log.debug("Started span [{}] for saga [{}] step {} (traceId: {})", stepName, sagaId, stepIndex, traceId);
        return span;
    }

    public void recordStepSuccess(Span span) {
        if (span != null) {
            span.setStatus(StatusCode.OK);
            span.end();
        }
    }

    public void recordStepFailure(Span span, Throwable failure) {
        if (span != null) {
            span.setStatus(StatusCode.ERROR, failure != null ? failure.getMessage() : "Unknown step failure");
            if (failure != null) {
                span.recordException(failure);
            }
            span.end();
        }
    }

    public void recordCompensationSpan(UUID sagaId, int stepIndex, String stepName, String cause) {
        TraceContextMetadata metadata = TraceContextHolder.get();
        String traceId = (metadata != null) ? metadata.traceId() : "none";

        Span span = tracer.spanBuilder("compensate-step: " + stepName)
                .setAttribute("saga.id", sagaId.toString())
                .setAttribute("saga.step.index", (long) stepIndex)
                .setAttribute("saga.step.name", stepName)
                .setAttribute("saga.compensation.cause", cause != null ? cause : "Unspecified")
                .setAttribute("w3c.traceId", traceId)
                .setAttribute("thread.name", Thread.currentThread().getName())
                .startSpan();
        span.setStatus(StatusCode.OK);
        span.end();
    }
}
