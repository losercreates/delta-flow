package io.deltaflow.saga.builder;

import io.deltaflow.saga.domain.SagaExecutionContext;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

public class SagaDefinition<I, R> {

    private final String name;
    private final List<SagaStepDefinition<I>> steps;
    private final BiFunction<SagaExecutionContext, I, R> completionHandler;
    private final Class<I> inputType;

    public SagaDefinition(
            String name,
            List<SagaStepDefinition<I>> steps,
            BiFunction<SagaExecutionContext, I, R> completionHandler
    ) {
        this(name, steps, completionHandler, null);
    }

    public SagaDefinition(
            String name,
            List<SagaStepDefinition<I>> steps,
            BiFunction<SagaExecutionContext, I, R> completionHandler,
            Class<I> inputType
    ) {
        this.name = Objects.requireNonNull(name, "Saga name must not be null");
        this.steps = Objects.requireNonNull(steps, "Steps list must not be null");
        this.completionHandler = completionHandler;
        this.inputType = inputType;
    }

    public String getName() {
        return name;
    }

    public List<SagaStepDefinition<I>> getSteps() {
        return steps;
    }

    public BiFunction<SagaExecutionContext, I, R> getCompletionHandler() {
        return completionHandler;
    }

    public Class<I> getInputType() {
        return inputType;
    }
}

