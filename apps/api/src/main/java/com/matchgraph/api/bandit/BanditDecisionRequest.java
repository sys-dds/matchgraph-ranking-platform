package com.matchgraph.api.bandit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record BanditDecisionRequest(
    UUID candidateProfileId,
    List<UUID> candidateProfileIds,
    String contextSegment,
    Map<String, Object> decisionContext,
    boolean applyToRanking
) {
}
