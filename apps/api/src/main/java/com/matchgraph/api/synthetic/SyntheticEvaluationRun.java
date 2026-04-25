package com.matchgraph.api.synthetic;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record SyntheticEvaluationRun(
    UUID id,
    UUID syntheticPopulationRunId,
    String rankingVersion,
    UUID decisionLogId,
    int k,
    String status,
    Map<String, Object> request,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    SyntheticEvaluationResult result
) {
}
