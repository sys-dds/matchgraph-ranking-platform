package com.matchgraph.api.reward;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record LongTermRewardLabel(
    UUID id,
    UUID runId,
    UUID trainingExampleId,
    UUID profileId,
    UUID candidateProfileId,
    BigDecimal shortTermReward,
    BigDecimal longTermReward,
    BigDecimal finalRewardValue,
    Map<String, Object> rewardComponents,
    OffsetDateTime createdAt
) {
}
