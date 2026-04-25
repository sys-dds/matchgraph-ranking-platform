package com.matchgraph.api.matching;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MatchResponse(
    UUID id,
    UUID profileAId,
    UUID profileBId,
    OffsetDateTime createdAt,
    String status
) {
}
