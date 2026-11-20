package io.deltaflow.chaos.domain;

import java.util.List;

public record ProxyStatusDto(
        String name,
        String listenAddress,
        String upstreamAddress,
        boolean enabled,
        List<ToxicDetailDto> activeToxics
) {}
