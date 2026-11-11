package io.deltaflow.saga.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public sealed interface SagaState permits 
    SagaState.Pending, 
    SagaState.Executing, 
    SagaState.Completed, 
    SagaState.Failed, 
    SagaState.Compensating,
    SagaState.Compensated {

    record Pending(
        UUID sagaId, 
        String tenantId, 
        String idempotencyKey, 
        String sagaType, 
        Map<String, Object> payload, 
        Instant createdAt
    ) implements SagaState {}

    record Executing(
        UUID sagaId, 
        String tenantId, 
        int currentStepIndex, 
        String currentStepName, 
        Instant stepStartedAt
    ) implements SagaState {}

    record Completed(
        UUID sagaId, 
        String tenantId, 
        Map<String, Object> finalResult, 
        Instant completedAt
    ) implements SagaState {}

    record Failed(
        UUID sagaId, 
        String tenantId, 
        int failedStepIndex, 
        String failureReason, 
        Instant failedAt
    ) implements SagaState {}

    record Compensating(
        UUID sagaId, 
        String tenantId, 
        int rollbackStepIndex, 
        String cause
    ) implements SagaState {}

    record Compensated(
        UUID sagaId, 
        String tenantId, 
        Map<String, Object> compensationResult, 
        Instant compensatedAt
    ) implements SagaState {}
}
