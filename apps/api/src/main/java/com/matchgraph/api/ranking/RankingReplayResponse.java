package com.matchgraph.api.ranking;

import java.util.List;
import java.util.UUID;

public record RankingReplayResponse(
    UUID originalDecisionLogId,
    UUID profileId,
    String rankingVersion,
    UUID featureSnapshotRunId,
    List<UUID> originalOrder,
    List<UUID> replayedOrder,
    boolean orderMatches,
    List<String> mismatches,
    List<RankingReplayItem> replayedItems
) {
}
