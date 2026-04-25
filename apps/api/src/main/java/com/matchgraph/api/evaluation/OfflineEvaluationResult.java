package com.matchgraph.api.evaluation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record OfflineEvaluationResult(
    UUID id,
    UUID runId,
    BigDecimal precisionAtK,
    BigDecimal recallAtK,
    BigDecimal mrr,
    BigDecimal ndcgAtK,
    BigDecimal coverage,
    BigDecimal diversity,
    BigDecimal negativeSignalPenalty,
    int evaluatedDecisionCount,
    int labelledDecisionCount,
    int unlabelledDecisionCount,
    int staleEmbeddingCount,
    Map<String, Object> result,
    OffsetDateTime createdAt
) {
}
