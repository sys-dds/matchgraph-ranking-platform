package com.matchgraph.api.interleaving;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record InterleavingSession(
    UUID id,
    UUID experimentId,
    UUID profileId,
    UUID featureSnapshotRunId,
    String rankerAVersion,
    String rankerBVersion,
    String method,
    Map<String, Object> context,
    String status,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    List<InterleavingItem> items
) {
}
