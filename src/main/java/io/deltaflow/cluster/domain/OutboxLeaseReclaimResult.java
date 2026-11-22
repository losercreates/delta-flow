package io.deltaflow.cluster.domain;

import java.time.Instant;

public record OutboxLeaseReclaimResult(
    int scannedCount,
    int reclaimedCount,
    int deadLetteredCount,
    Instant timestamp
) {}
