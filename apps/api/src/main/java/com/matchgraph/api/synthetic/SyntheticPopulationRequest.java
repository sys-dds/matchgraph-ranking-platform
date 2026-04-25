package com.matchgraph.api.synthetic;

import java.math.BigDecimal;
import java.util.Map;

public record SyntheticPopulationRequest(
    Long randomSeed,
    Integer profileCount,
    Integer clusterCount,
    BigDecimal compatibilityDensity,
    Map<String, Object> config
) {
}
