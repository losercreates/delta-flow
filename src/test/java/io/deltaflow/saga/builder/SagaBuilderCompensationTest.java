package io.deltaflow.saga.builder;

import io.deltaflow.saga.domain.SagaExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SagaBuilderCompensationTest {

    record TestRequest(String accountId, double amount) {}
    record StepOneResult(String transactionRef, double fee) {}
    record TestResult(String status) {}

    @Test
    @DisplayName("SagaBuilder registers compensation handler and receives forward execution context")
    void testCompensationRegistrationAndContextDelivery() {
        AtomicBoolean compensationExecuted = new AtomicBoolean(false);
        AtomicReference<String> recoveredRef = new AtomicReference<>();

        SagaDefinition<TestRequest, TestResult> saga = SagaBuilder
                .<TestRequest, TestResult>named("declarative-compensation-saga")
                .inputType(TestRequest.class)
                .step("step-1")
                    .action((ctx, req) -> new StepOneResult("tx-abc-999", 2.50))
                    .compensate((ctx, req) -> {
                        StepOneResult priorResult = ctx.getStepResult("step-1", StepOneResult.class);
                        if (priorResult != null) {
                            recoveredRef.set(priorResult.transactionRef());
                        }
                        compensationExecuted.set(true);
                    })
                .onCompletion((ctx, req) -> new TestResult("COMPLETED"))
                .build();

        assertThat(saga.getName()).isEqualTo("declarative-compensation-saga");
        assertThat(saga.getInputType()).isEqualTo(TestRequest.class);
        assertThat(saga.getSteps()).hasSize(1);

        SagaStepDefinition<TestRequest> step = saga.getSteps().get(0);
        assertThat(step.getCompensation()).isNotNull();

        // Simulate forward execution producing step result in context
        SagaExecutionContext context = new SagaExecutionContext(UUID.randomUUID(), "tenant-builder", "idemp-builder");
        StepOneResult forwardResult = (StepOneResult) step.getAction().apply(context, new TestRequest("acc-123", 50.0));
        context = context.withStepResult("step-1", forwardResult);

        // Execute compensation with populated context
        step.getCompensation().accept(context, new TestRequest("acc-123", 50.0));

        assertThat(compensationExecuted.get()).isTrue();
        assertThat(recoveredRef.get()).isEqualTo("tx-abc-999");
    }

    @Test
    @DisplayName("SagaBuilder rejects compensation definition without an active step")
    void testCompensateWithoutActiveStepThrows() {
        assertThatThrownBy(() -> SagaBuilder.<TestRequest, TestResult>named("invalid-saga")
                .compensate((ctx, req) -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot define compensation without an active step");
    }
}

