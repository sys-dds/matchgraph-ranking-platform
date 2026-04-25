package com.matchgraph.api.synthetic;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SyntheticPopulationRun(
    UUID id,
    long randomSeed,
    int profileCount,
    int clusterCount,
    BigDecimal compatibilityDensity,
    String status,
    Map<String, Object> config,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    List<SyntheticProfile> profiles
) {
}
