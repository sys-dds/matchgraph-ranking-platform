package com.matchgraph.api.shadow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ShadowRankingRun(
    UUID id,
    UUID profileId,
    UUID baselineDecisionLogId,
    String championRankingVersion,
    String challengerRankingVersion,
    UUID featureSnapshotRunId,
    Map<String, Object> rankingContext,
    String status,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    List<ShadowRankingItem> items
) {
}
