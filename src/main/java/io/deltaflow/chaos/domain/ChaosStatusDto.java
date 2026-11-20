package io.deltaflow.chaos.domain;

import io.deltaflow.dashboard.dto.CircuitBreakerStatusDto;
import java.util.List;

public record ChaosStatusDto(
        boolean toxiproxyEnabled,
        int activeToxicsCount,
        List<ProxyStatusDto> proxies,
        List<CircuitBreakerStatusDto> circuitBreakers
) {}
