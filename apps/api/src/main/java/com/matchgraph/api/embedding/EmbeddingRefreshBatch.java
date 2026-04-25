package com.matchgraph.api.embedding;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EmbeddingRefreshBatch(
    UUID id,
    String status,
    int maxItems,
    String selectionReason,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    Map<String, Object> metadata,
    List<EmbeddingRefreshBatchItem> items
) {
}
