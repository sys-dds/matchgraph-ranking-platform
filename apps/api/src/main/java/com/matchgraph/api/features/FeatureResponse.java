package com.matchgraph.api.features;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FeatureResponse(
    UUID ownerId,
    String featureKey,
    String featureValue,
    BigDecimal weight,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
