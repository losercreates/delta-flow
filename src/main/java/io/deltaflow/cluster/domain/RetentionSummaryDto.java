package io.deltaflow.cluster.domain;

import java.time.Instant;

public record RetentionSummaryDto(
    boolean enabled,
    int retentionDays,
    int batchSize,
    Instant lastRunAt,
    long totalPrunedRecords,
    Instant oldestRemainingResolvedRecord
) {}
