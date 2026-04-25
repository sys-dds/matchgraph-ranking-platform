package com.matchgraph.api.explainability;

import java.util.Map;
import java.util.UUID;

public record ExplanationEvidence(
    UUID retrievalRunId,
    UUID featureSnapshotRunId,
    UUID featureSnapshotId,
    UUID decisionLogId,
    UUID feedSnapshotId,
    String rankingVersion,
    String evidenceStatus,
    Map<String, Object> evidence
) {
}
