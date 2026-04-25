package com.matchgraph.api.ranking;

import java.util.UUID;

public record RankingRunRequest(
    UUID featureSnapshotRunId,
    String rankingVersion,
    Integer limit
) {
}
