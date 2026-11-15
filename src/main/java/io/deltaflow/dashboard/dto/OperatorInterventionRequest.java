package io.deltaflow.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OperatorInterventionRequest(
        @NotBlank(message = "Action is mandatory (FORCE_COMPENSATE or FORCE_RETRY)")
        String action,

        Integer stepIndex,

        @NotBlank(message = "Operator ID is mandatory")
        @Size(min = 3, message = "Operator ID must be at least 3 characters")
        String operatorId,

        @NotBlank(message = "Justification is mandatory")
        @Size(min = 10, message = "Justification must be at least 10 characters")
        String justification
) {
}
