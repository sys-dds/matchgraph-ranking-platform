package com.matchgraph.api.profile;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProfileSafetyStateResponse(
    UUID profileId,
    String safetyState,
    String reason,
    OffsetDateTime updatedAt
) {
}
