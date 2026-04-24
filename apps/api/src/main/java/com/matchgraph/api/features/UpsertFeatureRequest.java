package com.matchgraph.api.features;

import java.math.BigDecimal;

public record UpsertFeatureRequest(
    String featureKey,
    String featureValue,
    BigDecimal weight
) {
}
