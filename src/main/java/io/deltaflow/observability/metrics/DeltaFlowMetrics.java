package io.deltaflow.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DeltaFlowMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeSteps = new AtomicInteger(0);

    public DeltaFlowMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("deltaflow.saga.steps.active", activeSteps);
    }

    public void recordIdempotencyRequest(String status) {
        Counter.builder("deltaflow.idempotency.requests.total")
                .description("Total idempotency gate evaluations by status")
                .tag("status", status != null ? status.toLowerCase() : "unknown")
                .register(registry)
                .increment();
    }

    public void recordSagaDuration(String sagaName, String status, Duration duration) {
        Timer.builder("deltaflow.saga.duration")
                .description("End-to-end saga duration")
                .tag("saga", sagaName != null ? sagaName : "unnamed")
                .tag("status", status != null ? status : "UNKNOWN")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(duration);
    }

    public void incrementActiveSteps() {
        activeSteps.incrementAndGet();
    }

    public void decrementActiveSteps() {
        activeSteps.decrementAndGet();
    }

    public void recordStepTransition(String stepName, String outcome) {
        Counter.builder("deltaflow.saga.steps.total")
                .description("Total saga step executions by outcome")
                .tag("step", stepName != null ? stepName : "unknown")
                .tag("outcome", outcome != null ? outcome : "unknown")
                .register(registry)
                .increment();
    }

    public void recordLockAcquisition(String lockType, Duration duration) {
        Timer.builder("deltaflow.lock.acquisition.time")
                .description("Distributed lock acquisition time")
                .tag("lock_type", lockType != null ? lockType : "unknown")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(duration);
    }

    public void recordCompensation(String sagaName) {
        Counter.builder("deltaflow.compensations.total")
                .description("Total backward compensation workflows initiated")
                .tag("saga", sagaName != null ? sagaName : "unnamed")
                .register(registry)
                .increment();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }
}
