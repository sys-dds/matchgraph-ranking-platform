package com.matchgraph.api.shadow;

import java.util.Map;

public record ChampionChallengerConfigRequest(
    String configKey,
    String name,
    String status,
    String championRankingVersion,
    String challengerRankingVersion,
    Map<String, Object> guardrailConfig
) {
}
