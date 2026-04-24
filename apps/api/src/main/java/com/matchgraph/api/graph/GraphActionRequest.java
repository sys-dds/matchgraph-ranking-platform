package com.matchgraph.api.graph;

import java.util.UUID;

public record GraphActionRequest(
    UUID targetProfileId,
    String reason
) {
}
