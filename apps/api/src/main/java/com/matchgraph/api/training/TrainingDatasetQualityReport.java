package com.matchgraph.api.training;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record TrainingDatasetQualityReport(
    UUID id,
    UUID datasetRunId,
    int exampleCount,
    int labelledCount,
    int positiveCount,
    int negativeCount,
    int neutralCount,
    int missingFeatureCount,
    int staleEmbeddingCount,
    BigDecimal propensityCoverage,
    Map<String, Object> positionDistribution,
    Map<String, Object> sourceDistribution,
    Map<String, Object> labelDistribution,
    Map<String, Object> summary,
    OffsetDateTime createdAt
) {
}
