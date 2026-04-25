package com.matchgraph.api.training;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TrainingExample(
    UUID id,
    UUID datasetRunId,
    UUID profileId,
    UUID candidateProfileId,
    UUID decisionLogId,
    UUID feedSnapshotId,
    UUID feedItemId,
    UUID featureSnapshotRunId,
    UUID featureSnapshotId,
    String rankingVersion,
    Integer position,
    OffsetDateTime shownAt,
    List<String> sourceTypes,
    Map<String, Object> servingFeatures,
    Map<String, Object> offlineFeatures,
    Map<String, Object> label,
    BigDecimal labelValue,
    boolean labelPositive,
    boolean labelNegative,
    boolean labelNeutral,
    int labelWindowHours,
    BigDecimal propensity,
    String propensitySource,
    OffsetDateTime createdAt
) {
}
