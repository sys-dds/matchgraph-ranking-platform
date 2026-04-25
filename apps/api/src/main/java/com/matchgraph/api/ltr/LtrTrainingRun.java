package com.matchgraph.api.ltr;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LtrTrainingRun(
    UUID id,
    String modelKey,
    String versionKey,
    UUID datasetRunId,
    String algorithm,
    String status,
    List<String> featureNames,
    Map<String, Object> config,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt
) {
}
