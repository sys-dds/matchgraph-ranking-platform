package com.matchgraph.api.profile;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProfileEmbeddingStatusResponse(
    UUID profileId,
    String embeddingStatus,
    String activeVersionName,
    String activeModelName,
    Integer dimensions,
    OffsetDateTime embeddedAt
) {
}
