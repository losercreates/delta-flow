package io.deltaflow.saga.builder;

import io.deltaflow.saga.domain.SagaExecutionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public class SagaBuilder<I, R> {

    private final String name;
    private Class<I> inputType;
    private final List<SagaStepDefinition<I>> steps = new ArrayList<>();
    private String currentStepName;
    private BiFunction<SagaExecutionContext, I, ?> currentAction;
    private BiConsumer<SagaExecutionContext, I> currentCompensation;
    private String currentCircuitBreakerName;
    private String currentRetryName;
    private BiFunction<SagaExecutionContext, I, R> completionHandler;

    public static class ParallelStepBuilder<I> {
        private final List<ParallelBranchDefinition<I>> branches = new ArrayList<>();

        public ParallelStepBuilder<I> branch(String branchName, BiFunction<SagaExecutionContext, I, ?> action) {
            return branch(branchName, action, null);
        }

        public ParallelStepBuilder<I> branch(String branchName, BiFunction<SagaExecutionContext, I, ?> action, BiConsumer<SagaExecutionContext, I> compensation) {
            branches.add(new ParallelBranchDefinition<>(branchName, action, compensation));
            return this;
        }

        public List<ParallelBranchDefinition<I>> getBranches() {
            return Collections.unmodifiableList(branches);
        }
    }

    private SagaBuilder(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Saga name must not be null or blank");
        }
        this.name = name;
    }

    public static <I, R> SagaBuilder<I, R> named(String name) {
        return new SagaBuilder<>(name);
    }

    public SagaBuilder<I, R> inputType(Class<I> inputType) {
        this.inputType = inputType;
        return this;
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

    public SagaBuilder<I, R> withCircuitBreaker(String name) {
        if (currentStepName == null) {
            throw new IllegalStateException("Cannot configure circuit breaker without an active step");
        }
        this.currentCircuitBreakerName = name;
        return this;
    }

    public SagaBuilder<I, R> withRetry(String name) {
        if (currentStepName == null) {
            throw new IllegalStateException("Cannot configure retry without an active step");
        }
        this.currentRetryName = name;
        return this;
    }

    public SagaBuilder<I, R> parallelStep(String stepName, Consumer<ParallelStepBuilder<I>> configurer) {
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("Parallel step name must not be null or blank");
        }
        Objects.requireNonNull(configurer, "Configurer must not be null");
        flushCurrentStep();

        ParallelStepBuilder<I> builder = new ParallelStepBuilder<>();
        configurer.accept(builder);
        List<ParallelBranchDefinition<I>> branches = builder.getBranches();
        if (branches.isEmpty()) {
            throw new IllegalStateException("Parallel step '" + stepName + "' must define at least one branch");
        }

        steps.add(new SagaStepDefinition<>(stepName, (ctx, input) -> null, null, null, null, branches));
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
        return new SagaDefinition<>(name, Collections.unmodifiableList(new ArrayList<>(steps)), completionHandler, inputType);
    }

    private void flushCurrentStep() {
        if (currentStepName != null) {
            if (currentAction == null) {
                throw new IllegalStateException("Step '" + currentStepName + "' must define an action");
            }
            steps.add(new SagaStepDefinition<>(currentStepName, currentAction, currentCompensation, currentCircuitBreakerName, currentRetryName, List.of()));
            currentStepName = null;
            currentAction = null;
            currentCompensation = null;
            currentCircuitBreakerName = null;
            currentRetryName = null;
        }
    }
}
