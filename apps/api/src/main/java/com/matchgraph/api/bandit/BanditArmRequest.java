package com.matchgraph.api.bandit;

import java.math.BigDecimal;
import java.util.Map;

public record BanditArmRequest(
    String armKey,
    String sourceType,
    String strategy,
    BigDecimal weight,
    Map<String, Object> config
) {
}
