package com.matchgraph.api.features;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FeatureSnapshotRun(
    UUID id,
    UUID profileId,
    UUID retrievalRunId,
    String status,
    int candidateCount,
    int staleFeatureCount,
    int missingRequiredFeatureCount,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    List<CandidateFeatureSnapshot> candidates
) {
}
