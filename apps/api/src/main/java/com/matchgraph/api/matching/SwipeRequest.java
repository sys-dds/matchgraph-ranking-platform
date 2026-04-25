package com.matchgraph.api.matching;

import java.util.UUID;

public record SwipeRequest(
    UUID targetProfileId,
    String direction,
    String clientEventId
) {
}
