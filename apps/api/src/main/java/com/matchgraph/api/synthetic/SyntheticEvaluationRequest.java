package com.matchgraph.api.synthetic;

import java.util.UUID;

public record SyntheticEvaluationRequest(
    UUID syntheticPopulationRunId,
    UUID decisionLogId,
    String rankingVersion,
    Integer k
) {
}
