package com.matchgraph.api.embedding;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EmbeddingRefreshRequest(
    UUID id,
    UUID profileId,
    String status,
    String reason,
    String requestedBy,
    String currentEmbeddingStatus,
    String currentEmbeddingVersion,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
