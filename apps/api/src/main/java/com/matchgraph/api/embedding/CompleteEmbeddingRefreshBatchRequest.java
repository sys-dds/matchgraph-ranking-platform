package com.matchgraph.api.embedding;

import java.util.List;
import java.util.UUID;

public record CompleteEmbeddingRefreshBatchRequest(
    List<CompletedEmbeddingRefreshItem> items
) {
    public record CompletedEmbeddingRefreshItem(
        UUID profileId,
        String versionName,
        String modelName,
        List<Double> embedding
    ) {
    }
}
