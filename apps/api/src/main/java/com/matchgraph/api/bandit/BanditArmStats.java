package com.matchgraph.api.bandit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record BanditArmStats(
    UUID id,
    UUID policyId,
    UUID armId,
    String contextSegment,
    int decisionCount,
    int rewardCount,
    BigDecimal totalReward,
    BigDecimal averageReward,
    OffsetDateTime updatedAt
) {
}
