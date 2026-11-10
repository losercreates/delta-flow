package io.deltaflow.saga.api.dto;

import java.time.Instant;
import java.util.UUID;

public record TransferResponseDto(
        UUID sagaId,
        String transactionId,
        String status,
        Double amount,
        String currency,
        Integer stepsCompleted,
        Instant executedAt
) {}

