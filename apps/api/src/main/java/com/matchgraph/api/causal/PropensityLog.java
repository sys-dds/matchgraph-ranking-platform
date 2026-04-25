package com.matchgraph.api.causal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record PropensityLog(
    UUID id,
    UUID trainingExampleId,
    UUID decisionLogId,
    UUID feedSnapshotId,
    UUID feedItemId,
    UUID profileId,
    UUID candidateProfileId,
    BigDecimal propensity,
    String propensitySource,
    Map<String, Object> detail,
    OffsetDateTime createdAt
) {
}
