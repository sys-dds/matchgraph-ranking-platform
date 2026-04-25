package com.matchgraph.api.shadow;

import java.util.UUID;

public record ChampionChallengerEvaluateRequest(
    UUID baselineDecisionLogId,
    Integer limit
) {
}
