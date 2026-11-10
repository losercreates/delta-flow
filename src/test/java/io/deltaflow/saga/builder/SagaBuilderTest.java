package io.deltaflow.saga.builder;

import io.deltaflow.saga.domain.SagaExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SagaBuilderTest {

    record TestRequest(String accountId, double amount) {}
    record TestResult(String status, double amount) {}

    @Test
    @DisplayName("Declarative builder registers sequential steps with actions and compensations")
    void testBuildSagaSuccessfully() {
        SagaDefinition<TestRequest, TestResult> saga = SagaBuilder
                .<TestRequest, TestResult>named("sample-saga")
                .step("step-1")
                    .action((ctx, req) -> "step-1-ok")
                    .compensate((ctx, req) -> {})
                .step("step-2")
                    .action((ctx, req) -> "step-2-ok")
                .onCompletion((ctx, req) -> new TestResult("SUCCESS", req.amount()))
                .build();

        assertThat(saga.getName()).isEqualTo("sample-saga");
        assertThat(saga.getSteps()).hasSize(2);
        assertThat(saga.getSteps().get(0).getName()).isEqualTo("step-1");
        assertThat(saga.getSteps().get(1).getName()).isEqualTo("step-2");

        // Verify action execution
        SagaExecutionContext ctx = new SagaExecutionContext(UUID.randomUUID(), "default", "key-1");
        Object result1 = saga.getSteps().get(0).getAction().apply(ctx, new TestRequest("acc1", 100.0));
        assertThat(result1).isEqualTo("step-1-ok");
    }

    @Test
    @DisplayName("Builder validates step names and required actions")
    void testBuilderValidation() {
        assertThatThrownBy(() -> SagaBuilder.named(""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> SagaBuilder.<TestRequest, TestResult>named("bad-saga").build())
                .isInstanceOf(IllegalStateException.class);
    }
}

