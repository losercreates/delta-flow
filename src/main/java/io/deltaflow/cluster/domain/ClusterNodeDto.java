package io.deltaflow.cluster.domain;

import java.time.Instant;

public record ClusterNodeDto(
    String nodeId,
    String hostname,
    int port,
    String role,
    Instant startedAt,
    Instant lastHeartbeatAt,
    boolean isHealthy,
    String version
) {}
