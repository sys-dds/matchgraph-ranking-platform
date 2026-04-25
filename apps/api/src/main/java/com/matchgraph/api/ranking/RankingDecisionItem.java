package com.matchgraph.api.ranking;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RankingDecisionItem(
    UUID candidateProfileId,
    UUID featureSnapshotId,
    int position,
    BigDecimal baseScore,
    BigDecimal finalScore,
    List<RankingReason> reasons,
    List<RankingReason> diversityAdjustments,
    List<String> sourceTypes,
    OffsetDateTime createdAt
) {
}
