package com.matchgraph.api.synthetic;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record SyntheticEvaluationResult(
    UUID id,
    UUID evaluationRunId,
    BigDecimal precisionAtK,
    BigDecimal ndcgAtK,
    BigDecimal mrr,
    BigDecimal clusterCoverage,
    BigDecimal longTailCoverage,
    Map<String, Object> exposureDistribution,
    int safetyViolationCount,
    Map<String, Object> metrics,
    OffsetDateTime createdAt
) {
}
