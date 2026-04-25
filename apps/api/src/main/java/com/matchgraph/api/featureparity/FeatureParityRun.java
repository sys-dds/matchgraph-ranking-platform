package com.matchgraph.api.featureparity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FeatureParityRun(
    UUID id,
    UUID datasetRunId,
    UUID decisionLogId,
    String status,
    int comparedCount,
    int matchedCount,
    int skewedCount,
    int missingOnlineCount,
    int missingOfflineCount,
    int notComparableCount,
    Map<String, Object> toleranceConfig,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    List<FeatureParityResult> results
) {
}
