package com.matchgraph.api.features;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record CandidateFeatureValue(
    String featureKey,
    BigDecimal numericValue,
    String textValue,
    Map<String, Object> jsonValue,
    String freshnessStatus,
    OffsetDateTime createdAt
) {
}
