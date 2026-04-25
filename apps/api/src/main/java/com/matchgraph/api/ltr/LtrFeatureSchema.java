package com.matchgraph.api.ltr;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LtrFeatureSchema(
    UUID id,
    UUID modelId,
    String modelKey,
    String featureSchemaVersion,
    List<String> featureNames,
    Map<String, Object> schema,
    OffsetDateTime createdAt
) {
}
