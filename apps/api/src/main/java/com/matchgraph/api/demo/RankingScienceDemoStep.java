package com.matchgraph.api.demo;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RankingScienceDemoStep(
    UUID id,
    UUID demoRunId,
    String stepName,
    String stepStatus,
    Map<String, Object> stepResult,
    long durationMs,
    OffsetDateTime createdAt
) {
}
