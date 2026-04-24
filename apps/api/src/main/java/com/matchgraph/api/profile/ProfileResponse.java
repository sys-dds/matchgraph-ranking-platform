package com.matchgraph.api.profile;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProfileResponse(
    UUID id,
    String externalRef,
    String displayName,
    String profileType,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
