package com.matchgraph.api.experiment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RankingExperiment(
    UUID id,
    String experimentKey,
    String name,
    String status,
    BigDecimal trafficPercentage,
    BigDecimal holdoutPercentage,
    Map<String, Object> guardrailConfig,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<RankingExperimentVariant> variants
) {
}
