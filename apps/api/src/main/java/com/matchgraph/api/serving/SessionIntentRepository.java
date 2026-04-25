package com.matchgraph.api.serving;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.RecommendationSession;

import org.springframework.stereotype.Repository;

@Repository
public class SessionIntentRepository {

    private final RecommendationSurfaceRepository repository;

    public SessionIntentRepository(RecommendationSurfaceRepository repository) {
        this.repository = repository;
    }

    public UUID create(UUID profileId, int ttlMinutes) {
        return repository.createSession(profileId, ttlMinutes);
    }

    public RecommendationSession get(UUID sessionId) {
        return repository.session(sessionId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "recommendation session not found"));
    }

    public void event(UUID sessionId, String eventType, String sourceKey, UUID candidateId, Map<String, Object> metadata) {
        repository.insertIntentEvent(sessionId, eventType, sourceKey, candidateId, metadata);
    }

    public List<Map<String, Object>> events(UUID sessionId) {
        return repository.intentEvents(sessionId);
    }
}
