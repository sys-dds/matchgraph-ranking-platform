package com.matchgraph.api.serving;

import java.util.UUID;

import org.springframework.stereotype.Repository;

@Repository
public class FeedFatigueRepository {

    private final RecommendationSurfaceRepository repository;

    public FeedFatigueRepository(RecommendationSurfaceRepository repository) {
        this.repository = repository;
    }

    public boolean fatigued(UUID profileId, UUID candidateId, String source) {
        return repository.fatigued(profileId, candidateId, source);
    }

    public void suppress(UUID profileId, UUID candidateId, String source, String reason, int minutes, int repetitions) {
        repository.insertFatigue(profileId, candidateId, source, reason, minutes, repetitions);
    }
}
