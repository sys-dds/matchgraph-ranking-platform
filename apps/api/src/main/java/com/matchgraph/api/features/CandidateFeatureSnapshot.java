package com.matchgraph.api.features;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CandidateFeatureSnapshot(
    UUID id,
    UUID snapshotRunId,
    UUID candidateProfileId,
    UUID retrievalRunId,
    List<String> sourceTypes,
    String featureFreshnessStatus,
    OffsetDateTime createdAt,
    List<CandidateFeatureValue> values
) {
}
