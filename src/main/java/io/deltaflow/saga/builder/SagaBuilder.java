package io.deltaflow.saga.builder;

import io.deltaflow.saga.domain.SagaExecutionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public class SagaBuilder<I, R> {

    private final String name;
    private final List<SagaStepDefinition<I>> steps = new ArrayList<>();
    private String currentStepName;
    private BiFunction<SagaExecutionContext, I, ?> currentAction;
    private BiConsumer<SagaExecutionContext, I> currentCompensation;
    private BiFunction<SagaExecutionContext, I, R> completionHandler;

    private SagaBuilder(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Saga name must not be null or blank");
        }
        this.name = name;
    }

    public static <I, R> SagaBuilder<I, R> named(String name) {
        return new SagaBuilder<>(name);
    }

    public SagaBuilder<I, R> step(String stepName) {
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("Step name must not be null or blank");
        }
        flushCurrentStep();
        this.currentStepName = stepName;
        return this;
    }

    public SagaBuilder<I, R> action(BiFunction<SagaExecutionContext, I, ?> action) {
        if (currentStepName == null) {
            throw new IllegalStateException("Cannot define action without an active step");
        }
        this.currentAction = Objects.requireNonNull(action, "Action must not be null");
        return this;
    }

    public SagaBuilder<I, R> compensate(BiConsumer<SagaExecutionContext, I> compensation) {
        if (currentStepName == null) {
            throw new IllegalStateException("Cannot define compensation without an active step");
        }
        this.currentCompensation = compensation;
        return this;
    }

    public SagaBuilder<I, R> onCompletion(BiFunction<SagaExecutionContext, I, R> handler) {
        this.completionHandler = handler;
        return this;
    }

    public SagaDefinition<I, R> build() {
        flushCurrentStep();
        if (steps.isEmpty()) {
            throw new IllegalStateException("Saga must contain at least one step");
        }
        return new SagaDefinition<>(name, Collections.unmodifiableList(new ArrayList<>(steps)), completionHandler);
    }

    private void flushCurrentStep() {
        if (currentStepName != null) {
            if (currentAction == null) {
                throw new IllegalStateException("Step '" + currentStepName + "' must define an action");
            }
            steps.add(new SagaStepDefinition<>(currentStepName, currentAction, currentCompensation));
            currentStepName = null;
            currentAction = null;
            currentCompensation = null;
        }
    }
}

