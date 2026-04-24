package com.matchgraph.api.graph;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record GraphEdge(
    UUID sourceProfileId,
    UUID targetItemId,
    String edgeType,
    BigDecimal strength,
    OffsetDateTime lastSeenAt
) {
}
