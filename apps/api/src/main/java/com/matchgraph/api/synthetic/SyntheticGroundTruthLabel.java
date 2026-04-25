package com.matchgraph.api.synthetic;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record SyntheticGroundTruthLabel(
    UUID id,
    UUID runId,
    UUID actorProfileId,
    UUID candidateProfileId,
    String compatibilityLabel,
    BigDecimal expectedRelevance,
    Map<String, Object> labelReason,
    OffsetDateTime createdAt
) {
}
