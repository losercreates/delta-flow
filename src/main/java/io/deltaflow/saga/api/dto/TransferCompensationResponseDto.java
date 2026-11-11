package io.deltaflow.saga.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferCompensationResponseDto(
    UUID sagaId,
    String idempotencyKey,
    String status,
    String failedStep,
    String errorMessage,
    List<String> compensatedSteps,
    Instant timestamp
) {}

