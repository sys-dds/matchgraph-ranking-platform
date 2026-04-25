package com.matchgraph.api.realtime;

import java.util.List;
import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.CandidateInvalidation;

import org.springframework.stereotype.Repository;

@Repository
public class CandidateInvalidationRepository {
    private final RealtimeInteractionRepository repository;

    public CandidateInvalidationRepository(RealtimeInteractionRepository repository) {
        this.repository = repository;
    }

    public UUID create(UUID profileId, UUID candidateId, UUID eventId, String reason, boolean hard, Integer ttlMinutes, java.util.Map<String, Object> detail) {
        return repository.createInvalidation(profileId, candidateId, eventId, reason, hard, ttlMinutes, detail);
    }

    public List<CandidateInvalidation> list(UUID profileId) {
        return repository.invalidations(profileId);
    }

    public boolean invalidated(UUID profileId, UUID candidateId) {
        return repository.invalidated(profileId, candidateId);
    }
}
