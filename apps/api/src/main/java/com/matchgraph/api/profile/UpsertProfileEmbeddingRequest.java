package com.matchgraph.api.profile;

import java.util.List;

public record UpsertProfileEmbeddingRequest(
    String versionName,
    String modelName,
    List<Double> embedding
) {
}
