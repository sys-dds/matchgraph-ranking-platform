package com.matchgraph.api.exposure;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ExposureControlPolicy(
    UUID id,
    String policyKey,
    String name,
    String status,
    int dailyCap,
    int rolling7DayCap,
    int policyWindowHours,
    int policyWindowCap,
    BigDecimal longTailBoost,
    BigDecimal overexposureDownrank,
    BigDecimal newProfileMinimumBoost,
    Map<String, Object> config,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
