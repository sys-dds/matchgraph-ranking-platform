package com.matchgraph.api.scale;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RankingBenchmarkRun(
    UUID id,
    UUID seedRunId,
    int sampleProfileCount,
    boolean includeOfflineEvaluation,
    boolean cacheEnabled,
    String status,
    Map<String, Object> request,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt
) {
}
