package com.matchgraph.api.serving;

import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.FatiguePolicy;

import org.springframework.stereotype.Service;

@Service
public class FeedFatigueService {

    private final FeedFatigueRepository repository;
    private final FatiguePolicy policy = new FatiguePolicy(60, 15, 30, 3);

    public FeedFatigueService(FeedFatigueRepository repository) {
        this.repository = repository;
    }

    public boolean suppressed(UUID profileId, UUID candidateId, String source) {
        return repository.fatigued(profileId, candidateId, source);
    }

    public void recordServed(UUID profileId, UUID candidateId, String source, int repetitionCount) {
        if (repetitionCount >= policy.exposureThreshold()) {
            repository.suppress(profileId, candidateId, source, "temporary fatigue cooldown", policy.candidateCooldownMinutes(), repetitionCount);
        }
    }
}
