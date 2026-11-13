package io.deltaflow.saga.builder;

import io.deltaflow.saga.domain.SagaExecutionContext;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public record ParallelBranchDefinition<I>(
        String branchName,
        BiFunction<SagaExecutionContext, I, ?> action,
        BiConsumer<SagaExecutionContext, I> compensation
) {
    public ParallelBranchDefinition {
        Objects.requireNonNull(branchName, "Branch name must not be null");
        Objects.requireNonNull(action, "Branch action must not be null");
        if (compensation == null) {
            compensation = (ctx, req) -> {};
        }
    }

    public String getBranchName() {
        return branchName;
    }

    public BiFunction<SagaExecutionContext, I, ?> getAction() {
        return action;
    }

    public BiConsumer<SagaExecutionContext, I> getCompensation() {
        return compensation;
    }
}

