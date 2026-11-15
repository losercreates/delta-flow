package io.deltaflow.dashboard.dto;

public record SagaEdgeDto(
        String id,
        String source,
        String target,
        String type,
        boolean animated
) {
}
