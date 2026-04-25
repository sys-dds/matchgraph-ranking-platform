package com.matchgraph.api.bandit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record BanditReward(
    UUID id,
    UUID policyId,
    UUID armId,
    UUID decisionId,
    UUID profileId,
    UUID candidateProfileId,
    String rewardEventType,
    BigDecimal rewardValue,
    UUID interactionEventId,
    OffsetDateTime createdAt
) {
}
