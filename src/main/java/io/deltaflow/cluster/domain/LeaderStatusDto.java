package io.deltaflow.cluster.domain;

import java.time.Instant;

public record LeaderStatusDto(
    String leaderNodeId,
    boolean isCurrentNodeLeader,
    Instant leaseAcquiredAt,
    Instant leaseExpiresAt,
    long leaseDurationMs
) {}
