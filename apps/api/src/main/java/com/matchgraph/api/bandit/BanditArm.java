package com.matchgraph.api.bandit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record BanditArm(
    UUID id,
    UUID policyId,
    String armKey,
    String sourceType,
    String strategy,
    BigDecimal weight,
    Map<String, Object> config,
    OffsetDateTime createdAt
) {
}
