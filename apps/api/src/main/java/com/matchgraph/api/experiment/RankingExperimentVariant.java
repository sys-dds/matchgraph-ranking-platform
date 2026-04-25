package com.matchgraph.api.experiment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RankingExperimentVariant(
    UUID id,
    UUID experimentId,
    String variantKey,
    String rankingVersion,
    BigDecimal allocationPercentage,
    Map<String, Object> config,
    OffsetDateTime createdAt
) {
}
