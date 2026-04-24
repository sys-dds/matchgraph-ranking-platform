package com.matchgraph.api.graph;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record GraphEdgeResponse(
    UUID id,
    UUID sourceProfileId,
    UUID targetProfileId,
    String edgeType,
    String status,
    BigDecimal strength,
    String reason,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
