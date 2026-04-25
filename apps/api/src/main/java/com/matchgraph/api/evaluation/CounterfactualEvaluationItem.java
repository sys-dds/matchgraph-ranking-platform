package com.matchgraph.api.evaluation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CounterfactualEvaluationItem(
    UUID id,
    UUID runId,
    UUID candidateProfileId,
    Integer originalPosition,
    Integer counterfactualPosition,
    BigDecimal originalScore,
    BigDecimal counterfactualScore,
    Integer positionDelta,
    String topKChange,
    String labelEventType,
    Map<String, Object> metricDelta,
    OffsetDateTime createdAt
) {
}
