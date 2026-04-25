package com.matchgraph.api.synthetic;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record SyntheticProfile(
    UUID id,
    UUID runId,
    UUID profileId,
    String clusterId,
    String locationCluster,
    Map<String, Object> syntheticPreferenceVector,
    OffsetDateTime createdAt
) {
}
