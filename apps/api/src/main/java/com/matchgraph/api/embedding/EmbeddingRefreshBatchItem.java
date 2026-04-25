package com.matchgraph.api.embedding;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EmbeddingRefreshBatchItem(
    UUID id,
    UUID batchId,
    UUID requestId,
    UUID profileId,
    String status,
    String currentEmbeddingStatus,
    String currentEmbeddingVersion,
    String requestedReason,
    String completedEmbeddingVersion,
    OffsetDateTime completedAt,
    OffsetDateTime createdAt
) {
}
