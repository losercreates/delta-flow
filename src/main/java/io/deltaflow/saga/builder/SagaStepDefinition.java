package io.deltaflow.saga.builder;

import io.deltaflow.saga.domain.SagaExecutionContext;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public record SagaStepDefinition<I>(
        String name,
        BiFunction<SagaExecutionContext, I, ?> action,
        BiConsumer<SagaExecutionContext, I> compensation
) {
    public SagaStepDefinition {
        Objects.requireNonNull(name, "Step name must not be null");
        Objects.requireNonNull(action, "Step action must not be null");
        if (compensation == null) {
            compensation = (ctx, req) -> {};
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
}

