package com.matchgraph.api.scale;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RankingBenchmarkResult(
    UUID id,
    UUID benchmarkRunId,
    UUID profileId,
    long retrievalLatencyMs,
    long snapshotLatencyMs,
    long rankingLatencyMs,
    long feedLatencyMs,
    Long evaluationLatencyMs,
    int candidateCount,
    int cacheHitCount,
    int cacheMissCount,
    Map<String, Object> result,
    OffsetDateTime createdAt
) {
}
