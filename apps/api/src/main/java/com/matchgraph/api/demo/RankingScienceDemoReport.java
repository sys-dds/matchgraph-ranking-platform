package com.matchgraph.api.demo;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RankingScienceDemoReport(
    UUID demoRunId,
    UUID syntheticPopulationRunId,
    UUID actorProfileId,
    UUID retrievalRunId,
    UUID featureSnapshotRunId,
    UUID rankingDecisionLogId,
    UUID feedSnapshotId,
    String experimentKey,
    UUID assignmentId,
    UUID shadowRunId,
    List<UUID> explanationRequestIds,
    UUID banditDecisionId,
    UUID banditRewardId,
    UUID interleavingSessionId,
    String exposurePolicyKey,
    UUID offlineEvaluationRunId,
    UUID counterfactualRunId,
    UUID syntheticEvaluationRunId,
    Map<String, Object> keyMetrics,
    Map<String, Long> durationByStep,
    List<Map<String, Object>> skippedSteps
) {
}
