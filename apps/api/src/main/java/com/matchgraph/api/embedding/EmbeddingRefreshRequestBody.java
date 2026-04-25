package com.matchgraph.api.embedding;

public record EmbeddingRefreshRequestBody(
    String reason,
    String requestedBy
) {
}
