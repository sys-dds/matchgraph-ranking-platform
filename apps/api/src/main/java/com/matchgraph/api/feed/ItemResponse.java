package com.matchgraph.api.feed;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ItemResponse(
    UUID id,
    String externalRef,
    String title,
    String itemType,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
