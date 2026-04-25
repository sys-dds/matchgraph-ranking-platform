package com.matchgraph.api.evaluation;

import java.util.UUID;

public record CounterfactualEvaluationRequest(
    UUID baselineDecisionLogId,
    String candidateRankingVersion,
    Integer k
) {
}
