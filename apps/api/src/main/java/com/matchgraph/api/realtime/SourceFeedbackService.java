package com.matchgraph.api.realtime;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class SourceFeedbackService {

    private final RealtimeInteractionRepository repository;

    public SourceFeedbackService(RealtimeInteractionRepository repository) {
        this.repository = repository;
    }

    public void record(UUID profileId, UUID sessionId, String sourceKey, String signalType, BigDecimal value) {
        repository.insertSourceSignal(profileId, sessionId, sourceKey, signalType, value, Map.of("source", "adaptive_routing"));
    }

    public BigDecimal recentSignal(UUID profileId, UUID sessionId, String sourceKey) {
        return repository.recentSourceSignal(profileId, sessionId, sourceKey);
    }
}
