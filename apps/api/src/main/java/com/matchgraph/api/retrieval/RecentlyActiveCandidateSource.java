package com.matchgraph.api.retrieval;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class RecentlyActiveCandidateSource implements CandidateSource {

    private final RetrievalRepository retrievalRepository;

    public RecentlyActiveCandidateSource(RetrievalRepository retrievalRepository) {
        this.retrievalRepository = retrievalRepository;
    }

    @Override
    public CandidateSourceType sourceType() {
        return CandidateSourceType.RECENTLY_ACTIVE;
    }

    @Override
    public List<RetrievedCandidate> retrieve(UUID profileId, int limit) {
        return retrievalRepository.recentlyActive(profileId, limit);
    }
}
