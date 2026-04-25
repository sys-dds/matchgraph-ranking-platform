package com.matchgraph.api.realtime;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RealtimeModels {

    private RealtimeModels() {
    }

    public record RealtimeInteractionRequest(
        String eventKey,
        UUID profileId,
        UUID candidateProfileId,
        UUID feedSnapshotId,
        UUID feedItemId,
        UUID servingRequestId,
        UUID sessionId,
        String eventType,
        String sourceKey,
        OffsetDateTime occurredAt,
        Map<String, Object> metadata
    ) {
    }

    public record RealtimeInteractionEvent(
        UUID id,
        String eventKey,
        UUID profileId,
        UUID candidateProfileId,
        UUID feedSnapshotId,
        UUID feedItemId,
        UUID servingRequestId,
        UUID sessionId,
        String eventType,
        String sourceKey,
        OffsetDateTime occurredAt,
        OffsetDateTime receivedAt,
        Map<String, Object> metadata,
        String processingStatus,
        OffsetDateTime processedAt
    ) {
    }

    public record RealtimeInteractionResponse(
        RealtimeInteractionEvent event,
        boolean duplicate,
        List<String> sideEffects
    ) {
    }

    public record CandidateInvalidationRequest(
        UUID profileId,
        UUID candidateProfileId,
        UUID eventId,
        String reason,
        Boolean hardInvalidation,
        Integer ttlMinutes,
        Map<String, Object> detail
    ) {
    }

    public record CandidateInvalidation(
        UUID id,
        UUID profileId,
        UUID candidateProfileId,
        UUID eventId,
        String reason,
        boolean hardInvalidation,
        OffsetDateTime expiresAt,
        Map<String, Object> detail,
        List<String> targets
    ) {
    }

    public record NearlineFeatureMaterializationRequest(UUID profileId, UUID candidateProfileId) {
    }

    public record NearlineFeatureMaterializationRun(
        UUID id,
        UUID profileId,
        UUID candidateProfileId,
        String status,
        Map<String, Object> summary
    ) {
    }

    public record NearlineFeatureSnapshot(
        UUID profileId,
        UUID candidateProfileId,
        Map<String, Object> profileFeatures,
        Map<String, Object> candidateFeatures,
        Map<String, Object> pairFeatures
    ) {
    }

    public record LiveSessionIntentSnapshot(
        UUID id,
        UUID sessionId,
        UUID profileId,
        Map<String, java.math.BigDecimal> sourceWeights,
        Map<String, java.math.BigDecimal> positiveWeights,
        Map<String, java.math.BigDecimal> negativeWeights,
        java.math.BigDecimal confidenceScore,
        java.math.BigDecimal decayFactor,
        Map<String, Object> explanation,
        OffsetDateTime expiresAt
    ) {
    }

    public record SourceBudgetSnapshot(
        UUID id,
        UUID profileId,
        UUID sessionId,
        String sourceKey,
        int budgetBefore,
        int budgetAfter,
        Map<String, Object> reason
    ) {
    }

    public record DeltaFeedRefreshRequest(
        UUID triggerEventId,
        UUID servingRequestId,
        UUID sessionId,
        Integer maxNewItems,
        String reason
    ) {
    }

    public record DeltaFeedRefreshItem(UUID candidateProfileId, String action, Integer oldPosition, Integer newPosition, Map<String, Object> reason) {
    }

    public record DeltaFeedRefreshRun(
        UUID refreshRunId,
        List<UUID> removedCandidates,
        List<UUID> newCandidates,
        List<UUID> movedCandidates,
        List<UUID> unchangedCandidates,
        boolean degraded,
        String reason,
        UUID traceId
    ) {
    }

    public record FeatureFreshnessCheckRequest(
        UUID profileId,
        UUID candidateProfileId,
        List<String> requiredFeatureKeys,
        Long maxAgeMs,
        Boolean allowRebuild,
        Boolean allowFallback
    ) {
    }

    public record FeatureFreshnessResult(
        String featureKey,
        UUID profileId,
        UUID candidateProfileId,
        Long ageMs,
        long maxAgeMs,
        String status,
        boolean required,
        boolean fallbackUsed,
        Map<String, Object> detail
    ) {
    }

    public record FeatureFreshnessCheck(
        UUID checkId,
        UUID profileId,
        UUID candidateProfileId,
        String status,
        List<FeatureFreshnessResult> results,
        Map<String, Object> summary
    ) {
    }

    public record RealtimeFeedbackTrace(UUID traceId, String status, boolean degraded, Map<String, Object> summary, List<Map<String, Object>> steps) {
    }

    public record RealtimeFeedbackDemoRun(UUID demoRunId, String status, List<Map<String, Object>> steps, Map<String, Object> summary) {
    }
}
