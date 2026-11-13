package io.deltaflow.saga.builder;

import io.deltaflow.saga.domain.SagaExecutionContext;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public record SagaStepDefinition<I>(
        String name,
        BiFunction<SagaExecutionContext, I, ?> action,
        BiConsumer<SagaExecutionContext, I> compensation,
        String circuitBreakerName,
        String retryName,
        List<ParallelBranchDefinition<I>> parallelBranches
) {
    public SagaStepDefinition(String name, BiFunction<SagaExecutionContext, I, ?> action, BiConsumer<SagaExecutionContext, I> compensation) {
        this(name, action, compensation, null, null, List.of());
    }

    public SagaStepDefinition(String name, BiFunction<SagaExecutionContext, I, ?> action, BiConsumer<SagaExecutionContext, I> compensation, String circuitBreakerName, String retryName) {
        this(name, action, compensation, circuitBreakerName, retryName, List.of());
    }

    public SagaStepDefinition {
        Objects.requireNonNull(name, "Step name must not be null");
        if (compensation == null) {
            compensation = (ctx, req) -> {};
        }
        if (parallelBranches == null) {
            parallelBranches = List.of();
        } else {
            parallelBranches = Collections.unmodifiableList(parallelBranches);
        }
    }

    public String getName() {
        return name;
    }

    public BiFunction<SagaExecutionContext, I, ?> getAction() {
        return action;
    }

    public BiConsumer<SagaExecutionContext, I> getCompensation() {
        return compensation;
    }

    public String getCircuitBreakerName() {
        return circuitBreakerName;
    }

    public String getRetryName() {
        return retryName;
    }

    public List<ParallelBranchDefinition<I>> getParallelBranches() {
        return parallelBranches;
    }

    public boolean isParallel() {
        return parallelBranches != null && !parallelBranches.isEmpty();
    }
}
