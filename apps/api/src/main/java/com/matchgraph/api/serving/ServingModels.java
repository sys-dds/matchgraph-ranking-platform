package com.matchgraph.api.serving;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ServingModels {

    private ServingModels() {
    }

    public record SurfaceConfig(String surfaceKey, String status, String rankingVersion, List<String> allowedSources, int resultSize, int latencyBudgetMs, Map<String, Object> freshnessConfig, Map<String, Object> diversityConfig, Map<String, Object> fallbackConfig, Map<String, Object> safetyConfig) {
    }

    public record RecommendationSurfaceRequest(String surfaceKey, String status, String rankingVersion, List<String> allowedSources, Integer resultSize, Integer latencyBudgetMs, Map<String, Object> freshnessConfig, Map<String, Object> diversityConfig, Map<String, Object> fallbackConfig, Map<String, Object> safetyConfig) {
    }

    public record RecommendationSurfaceResponse(String surfaceKey, List<ServedItem> servedItems, boolean degraded, UUID traceId, List<String> warnings) {
    }

    public record MultiStageServingRequest(UUID sessionId, Integer limit, String rankingVersion, Boolean simulateSourceTimeout, Boolean simulateModelUnavailable, Boolean simulatePartialResult) {
    }

    public record MultiStageServingResponse(UUID requestId, String surfaceKey, List<ServedItem> servedItems, boolean degraded, UUID traceId, Map<String, Object> trace, List<String> warnings) {
    }

    public record ServedItem(UUID candidateProfileId, int position, BigDecimal score, List<String> sourceTypes, List<String> reasons) {
    }

    public record CandidateItem(UUID candidateProfileId, String sourceKey, BigDecimal score, List<String> reasons, boolean hardExcluded, String filteredReason) {
    }

    public record SourceRoutingPlan(UUID id, UUID requestId, List<String> sources, Map<String, Object> detail) {
    }

    public record SourceRoutingResult(UUID planId, List<SourceCallResult> sourceResults, Map<String, Object> budgetReasons) {
    }

    public record SourceCallResult(String sourceKey, int durationMs, int returnedCount, boolean timeout, boolean degraded, boolean fallbackUsed, String fallbackSource, String degradedReason, List<CandidateItem> candidates) {
    }

    public record PreRankRun(UUID id, List<CandidateItem> survivors, List<CandidateItem> filtered, Map<String, Object> summary) {
    }

    public record HeavyRankRun(UUID id, List<CandidateItem> ranked, boolean fallbackUsed, String fallbackReason, boolean modelBacked, int durationMs) {
    }

    public record SlateOptimizationRun(UUID id, List<ServedItem> selected, List<CandidateItem> dropped, boolean partialResult, String warning) {
    }

    public record RecommendationSession(UUID id, UUID profileId, OffsetDateTime expiresAt, String status) {
    }

    public record SessionIntentEvent(String eventType, String sourceKey, UUID candidateProfileId, Map<String, Object> metadata) {
    }

    public record SessionIntentState(UUID sessionId, UUID profileId, Map<String, BigDecimal> sourceWeights, Map<String, Object> explanation, OffsetDateTime expiresAt) {
    }

    public record FatiguePolicy(int candidateCooldownMinutes, int sourceCooldownMinutes, int clusterCooldownMinutes, int exposureThreshold) {
    }

    public record FatigueSuppressionWindow(UUID profileId, UUID candidateProfileId, String sourceType, String clusterKey, String suppressionReason, OffsetDateTime suppressUntil, BigDecimal fatigueScore, int repetitionCount) {
    }

    public record StageBudget(String stageName, int budgetMs) {
    }

    public record ServingQualityReport(UUID id, boolean degraded, int fallbackCount, int timeoutCount, int partialResultCount, List<String> warnings, Map<String, Object> summary) {
    }

    public record DegradationEvent(String stageName, String degradedReason, boolean fallbackUsed, boolean partialResult) {
    }

    public record ServingTrace(UUID requestId, Map<String, Object> trace) {
    }

    public record MultiStageServingDemoRun(UUID id, String status, List<Map<String, Object>> steps, Map<String, Object> summary) {
    }
}
