package com.matchgraph.api.bandit;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record BanditDecision(
    UUID id,
    UUID policyId,
    UUID armId,
    UUID profileId,
    UUID candidateProfileId,
    String contextSegment,
    Map<String, Object> decisionContext,
    String selectedArmKey,
    String selectionReason,
    boolean safe,
    OffsetDateTime createdAt
) {
}
