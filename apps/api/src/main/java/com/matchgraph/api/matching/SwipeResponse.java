package com.matchgraph.api.matching;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SwipeResponse(
    UUID id,
    UUID actorProfileId,
    UUID targetProfileId,
    String direction,
    String clientEventId,
    OffsetDateTime createdAt,
    boolean duplicate,
    boolean matchCreated,
    MatchResponse match
) {
}
