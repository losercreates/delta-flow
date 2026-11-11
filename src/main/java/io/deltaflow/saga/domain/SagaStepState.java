package io.deltaflow.saga.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public sealed interface SagaStepState permits 
    SagaStepState.NotStarted, 
    SagaStepState.Executing, 
    SagaStepState.Completed, 
    SagaStepState.Failed, 
    SagaStepState.Compensating,
    SagaStepState.Compensated {

    record NotStarted(UUID stepId, int stepIndex, String stepName) implements SagaStepState {}
    record Executing(UUID stepId, int stepIndex, String stepName, Map<String, Object> requestPayload, Instant startedAt) implements SagaStepState {}
    record Completed(UUID stepId, int stepIndex, String stepName, Map<String, Object> responsePayload, Instant completedAt) implements SagaStepState {}
    record Failed(UUID stepId, int stepIndex, String stepName, String errorDetails, Instant failedAt) implements SagaStepState {}
    record Compensating(UUID stepId, int stepIndex, String stepName, Instant startedAt) implements SagaStepState {}
    record Compensated(UUID stepId, int stepIndex, String stepName, Instant compensatedAt) implements SagaStepState {}
}
