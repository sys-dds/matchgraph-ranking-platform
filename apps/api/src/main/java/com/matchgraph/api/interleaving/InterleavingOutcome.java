package com.matchgraph.api.interleaving;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record InterleavingOutcome(
    UUID id,
    UUID sessionId,
    UUID interleavingItemId,
    UUID candidateProfileId,
    UUID interactionEventId,
    String outcomeEventType,
    String attributedRanker,
    BigDecimal rewardValue,
    String winner,
    Map<String, Object> summary,
    OffsetDateTime createdAt
) {
}
