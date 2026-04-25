package com.matchgraph.api.shadow;

import java.util.UUID;

public record ShadowRankingRunRequest(
    UUID baselineDecisionLogId,
    String challengerRankingVersion,
    Integer limit
) {
}
