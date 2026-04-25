package com.matchgraph.api.evaluation;

public record OfflineEvaluationResponse(
    OfflineEvaluationRun run,
    OfflineEvaluationResult result
) {
}
