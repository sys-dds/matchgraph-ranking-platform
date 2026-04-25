package com.matchgraph.api.ltr;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record LtrTrainingMetrics(
    UUID id,
    UUID trainingRunId,
    int trainingExampleCount,
    int validationExampleCount,
    int positiveCount,
    int negativeCount,
    BigDecimal validationPrecisionAtK,
    BigDecimal validationAverageReward,
    BigDecimal featureCoverage,
    Map<String, Object> metrics,
    OffsetDateTime createdAt
) {
}
