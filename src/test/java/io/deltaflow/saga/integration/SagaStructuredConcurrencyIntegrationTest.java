package io.deltaflow.saga.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.builder.SagaBuilder;
import io.deltaflow.saga.builder.SagaDefinition;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.exception.SagaCompensatedException;
import io.deltaflow.saga.service.SagaCoordinator;
import io.deltaflow.saga.service.SagaJournalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaStructuredConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SagaCoordinator coordinator;

    @Autowired
    private SagaJournalStore journalStore;

    record ParallelTestRequest(String orderId, long amount) {}

    @Test
    @DisplayName("Should execute parallel branches on Virtual Threads and aggregate results in context")
    void testParallelBranchesExecutionSuccess() {
        AtomicBoolean branch1Virtual = new AtomicBoolean(false);
        AtomicBoolean branch2Virtual = new AtomicBoolean(false);

        SagaDefinition<ParallelTestRequest, Map<String, Object>> saga = SagaBuilder.<ParallelTestRequest, Map<String, Object>>named("parallel-success-saga")
                .inputType(ParallelTestRequest.class)
                .step("prepare-order")
                    .action((ctx, req) -> Map.of("orderId", req.orderId(), "status", "PREPARED"))
                .parallelStep("post-order-actions", p -> p
                        .branch("send-notification", (ctx, req) -> {
                            branch1Virtual.set(Thread.currentThread().isVirtual());
                            return Map.of("notification", "SENT", "channel", "EMAIL");
                        })
                        .branch("audit-analytics", (ctx, req) -> {
                            branch2Virtual.set(Thread.currentThread().isVirtual());
                            return Map.of("analytics", "LOGGED", "amount", req.amount());
                        })
                )
                .onCompletion((ctx, req) -> {
                    Map<String, Object> parallelOut = (Map<String, Object>) ctx.getStepResult("post-order-actions");
                    return Map.of("finalStatus", "COMPLETED", "parallelResults", parallelOut);
                })
                .build();

        UUID sagaId = UUID.randomUUID();
        String idempotencyKey = "parallel-ok-" + sagaId;

        Map<String, Object> result = coordinator.executeSaga(saga, sagaId, "default", idempotencyKey, new ParallelTestRequest("ord-123", 500));

        assertThat(result).isNotNull();
        assertThat(result.get("finalStatus")).isEqualTo("COMPLETED");

        Map<String, Object> parallelOut = (Map<String, Object>) result.get("parallelResults");
        assertThat(parallelOut).isNotNull();
        assertThat(parallelOut).containsKey("send-notification");
        assertThat(parallelOut).containsKey("audit-analytics");

        assertThat(branch1Virtual.get()).isTrue();
        assertThat(branch2Virtual.get()).isTrue();

        SagaInstance instance = journalStore.findSaga(sagaId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo("COMPLETED");

        List<SagaStep> steps = journalStore.getStepsForSaga(sagaId);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(steps.get(1).getStatus()).isEqualTo("COMPLETED");
        assertThat(steps.get(1).getStepName()).isEqualTo("post-order-actions");
    }

    @Test
    @DisplayName("Should cancel sibling branch and initiate backward compensation when any branch fails")
    void testParallelBranchFailureCancelsSiblingsAndCompensates() throws Exception {
        AtomicBoolean step1Compensated = new AtomicBoolean(false);
        CountDownLatch branch2InterruptedLatch = new CountDownLatch(1);
        CountDownLatch branch1FailedLatch = new CountDownLatch(1);

        SagaDefinition<ParallelTestRequest, Map<String, Object>> saga = SagaBuilder.<ParallelTestRequest, Map<String, Object>>named("parallel-failure-saga")
                .inputType(ParallelTestRequest.class)
                .step("step-1")
                    .action((ctx, req) -> Map.of("status", "RESERVED"))
                    .compensate((ctx, req) -> step1Compensated.set(true))
                .parallelStep("parallel-step", p -> p
                        .branch("failing-branch", (ctx, req) -> {
                            branch1FailedLatch.countDown();
                            throw new RuntimeException("Simulated branch failure in parallelStep");
                        })
                        .branch("slow-sibling-branch", (ctx, req) -> {
                            try {
                                branch1FailedLatch.await(5, TimeUnit.SECONDS);
                                // Sleep to ensure cancellation is received
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                branch2InterruptedLatch.countDown();
                                Thread.currentThread().interrupt();
                            }
                            return Map.of("sibling", "completed");
                        })
                )
                .build();

        UUID sagaId = UUID.randomUUID();
        String idempotencyKey = "parallel-fail-" + sagaId;

        assertThatThrownBy(() -> coordinator.executeSaga(saga, sagaId, "default", idempotencyKey, new ParallelTestRequest("ord-456", 1000)))
                .isInstanceOf(SagaCompensatedException.class);

        // Verify slow sibling was cancelled / interrupted
        boolean wasInterrupted = branch2InterruptedLatch.await(3, TimeUnit.SECONDS);
        assertThat(wasInterrupted).isTrue();
        // Verify earlier step was compensated
        assertThat(step1Compensated.get()).isTrue();

        SagaInstance instance = journalStore.findSaga(sagaId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo("COMPENSATED");

        List<SagaStep> steps = journalStore.getStepsForSaga(sagaId);
        assertThat(steps.get(0).getStatus()).isEqualTo("COMPENSATED");
        assertThat(steps.get(1).getStatus()).isEqualTo("FAILED");
    }
}

