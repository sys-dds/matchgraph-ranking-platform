package com.matchgraph.api.metrics;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RankingMetricRow(
    String rankingVersion,
    String experimentKey,
    String variant,
    Boolean holdout,
    String candidateSource,
    int position,
    String positionBucket,
    String eventType,
    UUID profileId,
    UUID candidateProfileId,
    UUID decisionLogId,
    UUID feedSnapshotId,
    OffsetDateTime occurredAt
) {
}
