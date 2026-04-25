package com.matchgraph.api.exposure;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ExposureAdjustment(
    UUID id,
    UUID policyId,
    UUID candidateProfileId,
    UUID viewerProfileId,
    UUID decisionLogId,
    UUID feedSnapshotId,
    String adjustmentReason,
    BigDecimal boostAmount,
    BigDecimal downrankAmount,
    boolean bounded,
    boolean safetyOverridden,
    Map<String, Object> context,
    OffsetDateTime createdAt
) {
}
