package io.deltaflow.dashboard.dto;

import jakarta.validation.constraints.NotBlank;

public record DlqReplayRequest(
        @NotBlank String operatorId,
        @NotBlank String justification
) {}
