package com.matchgraph.api.causal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CausalEvaluationResult(
    UUID id,
    UUID runId,
    BigDecimal ipsPrecisionAtK,
    BigDecimal ipsNdcgAtK,
    BigDecimal weightedAverageReward,
    BigDecimal effectiveSampleSize,
    BigDecimal propensityCoverage,
    int excludedDueToMissingPropensity,
    boolean missingPropensityWarning,
    boolean highVarianceWarning,
    Map<String, Object> metrics,
    OffsetDateTime createdAt
) {
}
