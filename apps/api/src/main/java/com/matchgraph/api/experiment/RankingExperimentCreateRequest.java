package com.matchgraph.api.experiment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record RankingExperimentCreateRequest(
    String experimentKey,
    String name,
    String status,
    BigDecimal trafficPercentage,
    BigDecimal holdoutPercentage,
    Map<String, Object> guardrailConfig,
    List<RankingExperimentVariantRequest> variants
) {
}
