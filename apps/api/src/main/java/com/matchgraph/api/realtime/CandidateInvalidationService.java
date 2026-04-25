package com.matchgraph.api.realtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.CandidateInvalidation;
import com.matchgraph.api.realtime.RealtimeModels.CandidateInvalidationRequest;
import com.matchgraph.api.streaming.CacheInvalidationGraphService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class CandidateInvalidationService {
    private final CandidateInvalidationRepository repository;
    private final ObjectProvider<CacheInvalidationGraphService> cacheGraphService;

    public CandidateInvalidationService(CandidateInvalidationRepository repository, ObjectProvider<CacheInvalidationGraphService> cacheGraphService) {
        this.repository = repository;
        this.cacheGraphService = cacheGraphService;
    }

    public UUID create(CandidateInvalidationRequest request) {
        boolean hard = Boolean.TRUE.equals(request.hardInvalidation()) || List.of("BLOCKED", "REPORTED").contains(normalize(request.reason()));
        Integer ttl = hard ? null : request.ttlMinutes() == null ? 60 : request.ttlMinutes();
        UUID id = repository.create(request.profileId(), request.candidateProfileId(), request.eventId(), normalize(request.reason()), hard, ttl, request.detail());
        CacheInvalidationGraphService graph = cacheGraphService.getIfAvailable();
        if (graph != null && request.candidateProfileId() != null) {
            graph.invalidate("CANDIDATE", request.candidateProfileId().toString(), false);
        }
        return id;
    }

    public List<CandidateInvalidation> list(UUID profileId) {
        return repository.list(profileId);
    }

    public Map<String, Object> state(UUID profileId, UUID candidateId) {
        return Map.of("profileId", profileId, "candidateProfileId", candidateId, "invalidated", repository.invalidated(profileId, candidateId), "hardInvalidationCannotBeOverridden", true);
    }

    public boolean invalidated(UUID profileId, UUID candidateId) {
        return repository.invalidated(profileId, candidateId);
    }

    String normalize(String reason) {
        if (reason == null) return "PASSED";
        return switch (reason.trim().toUpperCase()) {
            case "BLOCK", "BLOCKED" -> "BLOCKED";
            case "REPORT", "REPORTED" -> "REPORTED";
            case "PASS", "PASSED" -> "PASSED";
            case "FEED_DISMISS", "FEED_DISMISSED" -> "FEED_DISMISSED";
            case "SOURCE_NEGATIVE" -> "SOURCE_NEGATIVE";
            case "STALE_FEATURES" -> "STALE_FEATURES";
            case "FATIGUED" -> "FATIGUED";
            default -> reason.trim().toUpperCase();
        };
    }
}
