package com.matchgraph.api.ltr;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LtrModelArtifact(
    UUID id,
    UUID modelVersionId,
    Map<String, Object> weights,
    List<String> featureNames,
    Map<String, Object> normalization,
    Map<String, Object> metadata,
    OffsetDateTime createdAt
) {
}
