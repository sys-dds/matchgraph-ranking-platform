package com.matchgraph.api.shadow;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ChampionChallengerDecision(
    UUID id,
    UUID configId,
    UUID shadowRunId,
    UUID profileId,
    UUID baselineDecisionLogId,
    int challengerImprovedCount,
    int challengerDegradedCount,
    BigDecimal topKOverlap,
    BigDecimal averagePositionDelta,
    int safetyRegressionCount,
    String guardrailStatus,
    String promotionRecommendation,
    Map<String, Object> summary,
    OffsetDateTime createdAt
) {
}
