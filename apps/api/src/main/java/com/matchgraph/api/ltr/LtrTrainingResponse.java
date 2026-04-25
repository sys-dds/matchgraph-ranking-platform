package com.matchgraph.api.ltr;

public record LtrTrainingResponse(LtrTrainingRun run, LtrTrainingMetrics metrics, LtrModelVersion modelVersion, LtrModelArtifact artifact) {
}
