package com.matchgraph.api.bandit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record BanditPolicy(
    UUID id,
    String policyKey,
    String name,
    String status,
    String algorithm,
    BigDecimal epsilon,
    Map<String, Object> rewardConfig,
    Map<String, Object> config,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<BanditArm> arms
) {
}
