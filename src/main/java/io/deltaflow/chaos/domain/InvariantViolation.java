package io.deltaflow.chaos.domain;

import java.util.UUID;

public record InvariantViolation(
        String violationType,
        String accountId,
        UUID sagaId,
        String expectedValue,
        String actualValue,
        String details
) {
}
