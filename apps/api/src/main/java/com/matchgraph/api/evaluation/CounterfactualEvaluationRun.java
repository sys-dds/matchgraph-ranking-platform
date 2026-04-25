package com.matchgraph.api.evaluation;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CounterfactualEvaluationRun(
    UUID id,
    UUID baselineDecisionLogId,
    String candidateRankingVersion,
    int k,
    String status,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt
) {
}
