package com.matchgraph.api.causal;

import java.util.UUID;

public record CausalEvaluationRequest(UUID datasetRunId, Integer k, Boolean useIpsWeights, Double maxWeight) {
}
