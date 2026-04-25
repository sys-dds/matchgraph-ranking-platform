package com.matchgraph.api.evaluation;

import java.util.List;

public record CounterfactualEvaluationResponse(
    CounterfactualEvaluationRun run,
    List<CounterfactualEvaluationItem> items
) {
}
