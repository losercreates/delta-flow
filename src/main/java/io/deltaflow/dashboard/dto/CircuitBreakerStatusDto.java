package io.deltaflow.dashboard.dto;

public record CircuitBreakerStatusDto(
        String name,
        String state,
        Float failureRate,
        Integer bufferedCalls
) {
}
