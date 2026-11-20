package io.deltaflow.chaos.domain;

import jakarta.validation.constraints.NotBlank;

public record ChaosInjectionRequest(
        @NotBlank String proxyName,
        @NotBlank String toxicType,
        Long latencyMs,
        Long jitterMs,
        Float dropRate,
        @NotBlank String operatorId,
        @NotBlank String justification
) {}
