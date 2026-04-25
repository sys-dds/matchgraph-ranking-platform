package com.matchgraph.api.reward;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record LongTermRewardResult(
    UUID id,
    UUID runId,
    int exampleCount,
    int labelledCount,
    BigDecimal averageShortTermReward,
    BigDecimal averageLongTermReward,
    BigDecimal averageFinalReward,
    Map<String, Object> summary,
    OffsetDateTime createdAt
) {
}
