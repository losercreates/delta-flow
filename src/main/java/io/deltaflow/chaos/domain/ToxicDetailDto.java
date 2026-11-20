package io.deltaflow.chaos.domain;

import java.util.Map;

public record ToxicDetailDto(
        String name,
        String type,
        String stream,
        float toxicity,
        Map<String, Object> attributes
) {}
