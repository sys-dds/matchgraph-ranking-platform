package com.matchgraph.api.feed;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.matchgraph.api.ranking.RankingReason;

public record FeedItem(
    UUID id,
    UUID feedSnapshotId,
    UUID retrievalRunId,
    UUID rankingDecisionLogId,
    UUID candidateProfileId,
    int position,
    BigDecimal score,
    List<RankingReason> rankingReasons,
    List<RankingReason> diversityAdjustments,
    List<String> sourceTypes,
    UUID featureSnapshotId,
    OffsetDateTime createdAt
) {
}
