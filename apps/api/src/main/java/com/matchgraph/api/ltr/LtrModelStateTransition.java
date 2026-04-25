package com.matchgraph.api.ltr;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record LtrModelStateTransition(
    UUID id,
    UUID modelVersionId,
    String fromStatus,
    String toStatus,
    String reason,
    Map<String, Object> metadata,
    OffsetDateTime createdAt
) {
}
