package com.matchgraph.api.realtime;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;

@Repository
public class NearlineFeatureRepository {
    private final RealtimeInteractionRepository repository;

    public NearlineFeatureRepository(RealtimeInteractionRepository repository) {
        this.repository = repository;
    }

    UUID materialize(UUID profileId, UUID candidateId) {
        UUID runId = repository.createMaterializationRun(profileId, candidateId, Map.of("source", "realtime_interaction_events", "idempotent", true));
        repository.upsertProfileFeature(runId, profileId, "views_1m", repository.eventCount(profileId, null, "PROFILE_VIEW", "1m"), null, "FRESH");
        repository.upsertProfileFeature(runId, profileId, "views_5m", repository.eventCount(profileId, null, "PROFILE_VIEW", "5m"), null, "FRESH");
        repository.upsertProfileFeature(runId, profileId, "views_1h", repository.eventCount(profileId, null, "PROFILE_VIEW", "1h"), null, "FRESH");
        repository.upsertProfileFeature(runId, profileId, "likes_1m", repository.eventCount(profileId, null, "LIKE", "1m"), null, "FRESH");
        repository.upsertProfileFeature(runId, profileId, "likes_5m", repository.eventCount(profileId, null, "LIKE", "5m"), null, "FRESH");
        repository.upsertProfileFeature(runId, profileId, "likes_1h", repository.eventCount(profileId, null, "LIKE", "1h"), null, "FRESH");
        repository.upsertProfileFeature(runId, profileId, "passes_1h", repository.eventCount(profileId, null, "PASS", "1h"), null, "FRESH");
        repository.upsertProfileFeature(runId, profileId, "blocks_24h", repository.eventCount(profileId, null, "BLOCK", "24h"), null, "FRESH");
        repository.upsertProfileFeature(runId, profileId, "reports_24h", repository.eventCount(profileId, null, "REPORT", "24h"), null, "FRESH");
        repository.upsertProfileFeature(runId, profileId, "recent_source_preference_json", null, repository.sourcePreference(profileId), "FRESH");
        if (candidateId != null) {
            BigDecimal views = repository.eventCount(profileId, candidateId, "PROFILE_VIEW", "1m");
            BigDecimal likes = repository.eventCount(profileId, candidateId, "LIKE", "5m");
            BigDecimal reports = repository.eventCount(profileId, candidateId, "REPORT", "24h");
            BigDecimal blocks = repository.eventCount(profileId, candidateId, "BLOCK", "24h");
            repository.upsertCandidateFeature(runId, candidateId, "candidate_views_1m", views, null, "FRESH");
            repository.upsertCandidateFeature(runId, candidateId, "candidate_likes_5m", likes, null, "FRESH");
            repository.upsertCandidateFeature(runId, candidateId, "candidate_reports_24h", reports, null, "FRESH");
            repository.upsertCandidateFeature(runId, candidateId, "candidate_blocks_24h", blocks, null, "FRESH");
            repository.upsertCandidateFeature(runId, candidateId, "candidate_hotness_score", views.add(likes), null, "FRESH");
            repository.upsertCandidateFeature(runId, candidateId, "candidate_safety_negative_score", reports.add(blocks), null, "FRESH");
            repository.upsertPairFeature(runId, profileId, candidateId, "recent_affinity_score", likes.subtract(repository.eventCount(profileId, candidateId, "PASS", "1h")), null, "FRESH");
            repository.upsertPairFeature(runId, profileId, candidateId, "recent_pass_count", repository.eventCount(profileId, candidateId, "PASS", "1h"), null, "FRESH");
            repository.upsertPairFeature(runId, profileId, candidateId, "recent_like_count", likes, null, "FRESH");
            repository.upsertPairFeature(runId, profileId, candidateId, "recent_negative_count", reports.add(blocks), null, "FRESH");
            repository.upsertPairFeature(runId, profileId, candidateId, "recent_candidate_fatigue", views, null, "FRESH");
        }
        return runId;
    }

    public Map<String, Object> profileFeatures(UUID profileId) {
        return repository.profileFeatures(profileId);
    }

    public Map<String, Object> candidateFeatures(UUID candidateId) {
        return repository.candidateFeatures(candidateId);
    }

    public Map<String, Object> pairFeatures(UUID profileId, UUID candidateId) {
        return repository.pairFeatures(profileId, candidateId);
    }
}
