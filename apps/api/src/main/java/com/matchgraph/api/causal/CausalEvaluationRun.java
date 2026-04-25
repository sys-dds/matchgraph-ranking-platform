package com.matchgraph.api.causal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CausalEvaluationRun(
    UUID id,
    UUID datasetRunId,
    int k,
    boolean useIpsWeights,
    BigDecimal maxWeight,
    String status,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    CausalEvaluationResult result
) {
}
