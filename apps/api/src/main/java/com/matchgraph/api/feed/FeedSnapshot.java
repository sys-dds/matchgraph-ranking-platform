package com.matchgraph.api.feed;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FeedSnapshot(
    UUID id,
    UUID profileId,
    UUID retrievalRunId,
    UUID featureSnapshotRunId,
    UUID rankingDecisionLogId,
    String rankingVersion,
    String status,
    OffsetDateTime createdAt,
    List<FeedItem> items
) {
}
