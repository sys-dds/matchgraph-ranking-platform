package com.matchgraph.api.featureparity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record FeatureParityResult(
    UUID id,
    UUID runId,
    UUID trainingExampleId,
    String featureName,
    Object onlineValue,
    Object offlineValue,
    BigDecimal numericDelta,
    String status,
    Map<String, Object> detail,
    OffsetDateTime createdAt
) {
}
