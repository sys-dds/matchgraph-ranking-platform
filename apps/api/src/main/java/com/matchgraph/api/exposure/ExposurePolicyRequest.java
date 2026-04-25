package com.matchgraph.api.exposure;

import java.math.BigDecimal;
import java.util.Map;

public record ExposurePolicyRequest(
    String policyKey,
    String name,
    String status,
    Integer dailyCap,
    Integer rolling7DayCap,
    Integer policyWindowHours,
    Integer policyWindowCap,
    BigDecimal longTailBoost,
    BigDecimal overexposureDownrank,
    BigDecimal newProfileMinimumBoost,
    Map<String, Object> config
) {
}
