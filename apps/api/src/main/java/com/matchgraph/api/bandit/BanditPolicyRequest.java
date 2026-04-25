package com.matchgraph.api.bandit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record BanditPolicyRequest(
    String policyKey,
    String name,
    String status,
    String algorithm,
    BigDecimal epsilon,
    Map<String, Object> rewardConfig,
    Map<String, Object> config,
    List<BanditArmRequest> arms
) {
}
