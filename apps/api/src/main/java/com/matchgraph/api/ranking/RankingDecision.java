package com.matchgraph.api.ranking;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RankingDecision(
    UUID id,
    UUID profileId,
    UUID retrievalRunId,
    UUID featureSnapshotRunId,
    String rankingVersion,
    String decisionType,
    int candidateCount,
    int servedCount,
    List<UUID> candidatePool,
    OffsetDateTime createdAt,
    List<RankingDecisionItem> items
) {
}
