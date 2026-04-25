package com.matchgraph.api.ltr;

import java.util.Map;
import java.util.UUID;

public record CreateLtrModelVersionRequest(
    String versionKey,
    String modelType,
    String featureSchemaVersion,
    UUID trainingDatasetRunId,
    Map<String, Object> eligibility
) {
}
