package com.matchgraph.api.interaction;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record InteractionResponse(
    UUID id,
    String clientEventId,
    UUID actorProfileId,
    UUID targetProfileId,
    String eventType,
    OffsetDateTime occurredAt,
    String requestId,
    UUID retrievalRunId,
    String candidateSource,
    String rankingVersion,
    String experimentId,
    String variant,
    Integer feedPosition,
    Map<String, Object> metadata,
    OffsetDateTime createdAt
) {
}
