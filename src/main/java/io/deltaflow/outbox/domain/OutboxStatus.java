package io.deltaflow.outbox.domain;

public enum OutboxStatus {
    PENDING,
    IN_FLIGHT,
    PUBLISHED,
    DEAD_LETTER
}

