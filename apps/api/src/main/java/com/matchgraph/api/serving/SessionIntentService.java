package com.matchgraph.api.serving;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.RecommendationSession;
import com.matchgraph.api.serving.ServingModels.SessionIntentEvent;
import com.matchgraph.api.serving.ServingModels.SessionIntentState;
import com.matchgraph.api.realtime.LiveSessionIntentRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class SessionIntentService {

    private final SessionIntentRepository repository;
    private final ObjectProvider<LiveSessionIntentRepository> liveIntentRepository;

    public SessionIntentService(SessionIntentRepository repository, ObjectProvider<LiveSessionIntentRepository> liveIntentRepository) {
        this.repository = repository;
        this.liveIntentRepository = liveIntentRepository;
    }

    public RecommendationSession create(UUID profileId) {
        UUID id = repository.create(profileId, 60);
        return repository.get(id);
    }

    public SessionIntentState record(UUID sessionId, SessionIntentEvent event) {
        repository.event(sessionId, event.eventType(), event.sourceKey(), event.candidateProfileId(), event.metadata());
        return state(sessionId);
    }

    public SessionIntentState state(UUID sessionId) {
        RecommendationSession session = repository.get(sessionId);
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        Map<String, Object> explanation = new LinkedHashMap<>();
        for (Map<String, Object> event : repository.events(sessionId)) {
            String source = String.valueOf(event.get("sourceKey"));
            if (source.isBlank()) {
                continue;
            }
            BigDecimal delta = switch (String.valueOf(event.get("eventType"))) {
                case "PROFILE_VIEW", "LIKE", "SOURCE_POSITIVE" -> BigDecimal.valueOf(0.25);
                case "PASS", "BLOCK", "REPORT", "SOURCE_IGNORED" -> BigDecimal.valueOf(-0.35);
                default -> BigDecimal.ZERO;
            };
            weights.merge(source, delta, BigDecimal::add);
        }
        liveIntentRepository.ifAvailable(live -> live.latest(sessionId).ifPresent(snapshot -> {
            snapshot.sourceWeights().forEach((source, weight) -> weights.merge(source, weight, BigDecimal::add));
            explanation.put("liveIntentV2Applied", true);
            explanation.put("liveIntentConfidence", snapshot.confidenceScore());
            explanation.put("liveIntentDecayFactor", snapshot.decayFactor());
        }));
        explanation.put("semantics", "short-lived explainable source preference weights; safety still wins");
        explanation.put("expiresAt", session.expiresAt().toString());
        return new SessionIntentState(sessionId, session.profileId(), weights, explanation, session.expiresAt());
    }
}
