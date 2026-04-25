package com.matchgraph.api.experiment;

import java.math.BigDecimal;
import java.util.Map;

public record RankingExperimentVariantRequest(
    String variantKey,
    String rankingVersion,
    BigDecimal allocationPercentage,
    Map<String, Object> config
) {
}
