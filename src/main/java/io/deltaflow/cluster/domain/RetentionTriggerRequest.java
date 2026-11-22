package io.deltaflow.cluster.domain;

import jakarta.validation.constraints.NotBlank;

public record RetentionTriggerRequest(
    Integer retentionDays,
    Integer batchSize,
    @NotBlank String operatorId,
    @NotBlank String justification
) {}
