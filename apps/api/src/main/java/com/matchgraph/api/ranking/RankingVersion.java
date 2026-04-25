package com.matchgraph.api.ranking;

import java.time.OffsetDateTime;

public record RankingVersion(
    String versionKey,
    String description,
    boolean active,
    RankingPolicy policy,
    OffsetDateTime createdAt
) {
}
