package com.matchgraph.api.interleaving;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record InterleavingItem(
    UUID id,
    UUID sessionId,
    UUID candidateProfileId,
    int position,
    String attributedRanker,
    Integer rankerAPosition,
    Integer rankerBPosition,
    Map<String, Object> score,
    OffsetDateTime createdAt
) {
}
