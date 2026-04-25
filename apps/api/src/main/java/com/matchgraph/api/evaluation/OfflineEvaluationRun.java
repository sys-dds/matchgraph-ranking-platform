package com.matchgraph.api.evaluation;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record OfflineEvaluationRun(
    UUID id,
    String rankingVersion,
    String experimentKey,
    OffsetDateTime from,
    OffsetDateTime to,
    int k,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    Map<String, Object> request
) {
}
