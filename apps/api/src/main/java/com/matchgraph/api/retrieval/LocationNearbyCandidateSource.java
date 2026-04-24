package com.matchgraph.api.retrieval;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class LocationNearbyCandidateSource implements CandidateSource {

    private final RetrievalRepository retrievalRepository;

    public LocationNearbyCandidateSource(RetrievalRepository retrievalRepository) {
        this.retrievalRepository = retrievalRepository;
    }

    @Override
    public CandidateSourceType sourceType() {
        return CandidateSourceType.LOCATION_NEARBY;
    }

    @Override
    public List<RetrievedCandidate> retrieve(UUID profileId, int limit) {
        return retrievalRepository.locationNearby(profileId, limit);
    }
}
