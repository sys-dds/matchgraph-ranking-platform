package com.matchgraph.api.shadow;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ShadowRankingItem(
    UUID id,
    UUID shadowRunId,
    UUID candidateProfileId,
    Integer championPosition,
    Integer challengerPosition,
    BigDecimal championScore,
    BigDecimal challengerScore,
    Integer positionDelta,
    BigDecimal scoreDelta,
    Map<String, Object> reasonDelta,
    OffsetDateTime createdAt
) {
}
