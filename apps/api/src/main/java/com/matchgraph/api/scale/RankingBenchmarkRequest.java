package com.matchgraph.api.scale;

import java.util.UUID;

public record RankingBenchmarkRequest(
    UUID seedRunId,
    Integer sampleProfileCount,
    Boolean includeOfflineEvaluation,
    Boolean cacheEnabled
) {
}
