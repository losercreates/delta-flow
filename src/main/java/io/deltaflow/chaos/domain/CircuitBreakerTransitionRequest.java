package io.deltaflow.chaos.domain;

import jakarta.validation.constraints.NotBlank;

public record CircuitBreakerTransitionRequest(
        @NotBlank String targetState,
        @NotBlank String operatorId,
        @NotBlank String justification
) {}
