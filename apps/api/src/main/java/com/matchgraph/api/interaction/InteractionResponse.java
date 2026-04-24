package com.matchgraph.api.interaction;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InteractionResponse(
    UUID id,
    UUID profileId,
    UUID itemId,
    String interactionType,
    OffsetDateTime occurredAt
) {
}
