package com.matchgraph.api.demo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RankingScienceDemoRun(
    UUID id,
    long seed,
    Map<String, Object> config,
    String status,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    List<RankingScienceDemoStep> steps
) {
}
