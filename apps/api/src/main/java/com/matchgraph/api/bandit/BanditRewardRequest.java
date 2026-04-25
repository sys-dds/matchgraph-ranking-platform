package com.matchgraph.api.bandit;

import java.math.BigDecimal;
import java.util.UUID;

public record BanditRewardRequest(
    UUID decisionId,
    String policyKey,
    UUID profileId,
    UUID candidateProfileId,
    String rewardEventType,
    BigDecimal rewardValue,
    UUID interactionEventId
) {
}
