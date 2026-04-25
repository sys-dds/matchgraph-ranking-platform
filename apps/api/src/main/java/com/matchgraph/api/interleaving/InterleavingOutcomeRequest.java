package com.matchgraph.api.interleaving;

import java.math.BigDecimal;
import java.util.UUID;

public record InterleavingOutcomeRequest(
    UUID candidateProfileId,
    UUID interactionEventId,
    String outcomeEventType,
    BigDecimal rewardValue
) {
}
