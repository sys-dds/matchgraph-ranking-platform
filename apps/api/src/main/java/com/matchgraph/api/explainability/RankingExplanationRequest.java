package com.matchgraph.api.explainability;

import java.util.UUID;

public record RankingExplanationRequest(
    UUID profileId,
    UUID candidateProfileId,
    UUID decisionLogId,
    UUID feedSnapshotId,
    String explanationType
) {
}
