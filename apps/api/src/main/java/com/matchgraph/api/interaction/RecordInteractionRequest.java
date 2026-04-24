package com.matchgraph.api.interaction;

import java.util.Map;
import java.util.UUID;

public record RecordInteractionRequest(
    UUID profileId,
    UUID itemId,
    String interactionType,
    Map<String, Object> metadata
) {
}
