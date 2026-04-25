package com.matchgraph.api.streaming;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StreamingModels {

    private StreamingModels() {
    }

    public record StreamingFeatureWindowRequest(UUID profileId, UUID candidateProfileId, String sourceKey, String surfaceKey) {
    }

    public record FeatureWindowRun(UUID id, String status, boolean approximate, Map<String, Object> summary) {
    }

    public record ProfileFeatureWindow(UUID profileId, String windowKey, long views, long likes, long passes, long blocks, long reports, long feedDismisses, long matchCreations, boolean approximate) {
    }

    public record CandidateFeatureWindow(UUID candidateProfileId, String windowKey, long views, long likes, long passes, long blocks, long reports, long matchCreations, BigDecimal safetyNegativeScore, boolean approximate) {
    }

    public record SourceFeatureWindow(String sourceKey, String windowKey, long returnedCandidates, long timeoutCount, long fallbackCount, long emptyResultCount, BigDecimal latencyMsAvg, boolean approximate) {
    }

    public record SurfaceFeatureWindow(String surfaceKey, String windowKey, long requests, long degradedResponses, long partialResponses, BigDecimal servedCountAvg, long fallbackCount, boolean approximate) {
    }

    public record CandidateTrendRun(UUID id, String status, Map<String, Object> summary, List<CandidateTrendScore> scores) {
    }

    public record CandidateTrendScore(UUID id, UUID candidateProfileId, String trendDirection, BigDecimal velocityScore, BigDecimal hotnessScore, BigDecimal safetyNegativeScore, BigDecimal boundedBoost, boolean boostAllowed, String boostBlockedReason, Map<String, Object> explanation) {
    }

    public record SourceHealthSnapshot(UUID id, String sourceKey, String healthStatus, BigDecimal latencyP50Ms, BigDecimal latencyP95Ms, BigDecimal timeoutRate, BigDecimal emptyResultRate, BigDecimal qualityScore, Map<String, Object> evidence) {
    }

    public record SourceBackpressureAction(UUID id, String sourceKey, String action, int budgetBefore, int budgetAfter, OffsetDateTime expiresAt, Map<String, Object> reason) {
    }

    public record LiveQualityAnomalyRun(UUID id, String status, boolean approximate, Map<String, Object> summary, List<LiveQualityAnomaly> anomalies) {
    }

    public record LiveQualityAnomaly(UUID id, String anomalyType, String severity, String affectedSurface, String affectedSource, String recommendedAction, BigDecimal observedValue, BigDecimal baselineValue, BigDecimal thresholdValue, Map<String, Object> evidence) {
    }

    public record ExperimentGuardrailRun(UUID id, String status, Map<String, Object> summary, List<ExperimentGuardrailDecision> decisions) {
    }

    public record ExperimentGuardrailDecision(UUID id, String experimentKey, String variantKey, String guardrailStatus, String decisionAction, Map<String, Object> reason) {
    }

    public record ModelKillSwitchState(UUID id, String modelKey, String versionKey, String status, String killReason, boolean requireRolloutGateReapproval, Map<String, Object> detail) {
    }

    public record CacheInvalidationNode(UUID id, String nodeType, String nodeRef, Map<String, Object> detail) {
    }

    public record CacheInvalidationAction(UUID id, String actionType, String targetNodeType, String targetNodeRef, String executionStatus, Map<String, Object> reason) {
    }

    public record CacheInvalidationRun(UUID id, String triggerNodeType, String triggerNodeRef, boolean globalInvalidation, String status, List<CacheInvalidationAction> actions, Map<String, Object> summary) {
    }

    public record RealtimeRecoveryTrace(UUID traceId, String scenarioKey, String status, boolean degraded, Map<String, Object> summary, List<Map<String, Object>> steps) {
    }

    public record RealtimeOperationsDemoRun(UUID demoRunId, String status, List<Map<String, Object>> steps, Map<String, Object> summary) {
    }
}
