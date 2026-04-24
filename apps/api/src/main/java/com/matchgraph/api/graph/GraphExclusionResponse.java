package com.matchgraph.api.graph;

import java.util.UUID;

public record GraphExclusionResponse(
    UUID profileId,
    String reason
) {
}
