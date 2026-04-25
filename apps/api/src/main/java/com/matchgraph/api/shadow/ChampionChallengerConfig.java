package com.matchgraph.api.shadow;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ChampionChallengerConfig(
    UUID id,
    String configKey,
    String name,
    String status,
    String championRankingVersion,
    String challengerRankingVersion,
    Map<String, Object> guardrailConfig,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
