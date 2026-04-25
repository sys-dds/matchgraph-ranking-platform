package com.matchgraph.api.scale;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ScaleSeedRun(
    UUID id,
    long randomSeed,
    int profileCount,
    int edgeCount,
    int interactionCount,
    boolean embeddingEnabled,
    boolean locationEnabled,
    int interestClusterCount,
    boolean allowLarge,
    String status,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt
) {
}
