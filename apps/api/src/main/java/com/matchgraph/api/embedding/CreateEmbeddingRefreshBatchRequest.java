package com.matchgraph.api.embedding;

public record CreateEmbeddingRefreshBatchRequest(
    Integer maxItems,
    String selectionReason
) {
}
