package com.matchgraph.api.scale;

public record ScaleSeedRequest(
    Integer profileCount,
    Integer edgeCount,
    Integer interactionCount,
    Boolean embeddingEnabled,
    Boolean locationEnabled,
    Integer interestClusterCount,
    Long randomSeed,
    Boolean allowLarge
) {
}
