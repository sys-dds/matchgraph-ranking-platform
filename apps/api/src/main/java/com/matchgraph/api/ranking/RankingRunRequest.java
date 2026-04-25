package com.matchgraph.api.ranking;

import java.util.UUID;

public record RankingRunRequest(
    UUID featureSnapshotRunId,
    String rankingVersion,
    Integer limit,
    String experimentKey
) {
    public RankingRunRequest(UUID featureSnapshotRunId, String rankingVersion, Integer limit) {
        this(featureSnapshotRunId, rankingVersion, limit, null);
    }
}
