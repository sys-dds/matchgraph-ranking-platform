package com.matchgraph.api.ltr;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record LtrModelVersion(
    UUID id,
    UUID modelId,
    String modelKey,
    String versionKey,
    String modelType,
    String status,
    String featureSchemaVersion,
    UUID trainingDatasetRunId,
    UUID trainingRunId,
    Map<String, Object> metrics,
    Map<String, Object> eligibility,
    OffsetDateTime createdAt,
    OffsetDateTime activatedAt
) {
}
