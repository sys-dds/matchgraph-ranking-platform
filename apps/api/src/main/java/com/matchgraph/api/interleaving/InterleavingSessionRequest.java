package com.matchgraph.api.interleaving;

import java.util.Map;
import java.util.UUID;

public record InterleavingSessionRequest(
    UUID featureSnapshotRunId,
    Integer limit,
    Map<String, Object> rankingContext
) {
}
