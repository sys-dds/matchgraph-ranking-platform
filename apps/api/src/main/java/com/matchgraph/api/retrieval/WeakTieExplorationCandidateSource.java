package com.matchgraph.api.retrieval;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class WeakTieExplorationCandidateSource implements CandidateSource {

    private final RetrievalRepository retrievalRepository;

    public WeakTieExplorationCandidateSource(RetrievalRepository retrievalRepository) {
        this.retrievalRepository = retrievalRepository;
    }

    @Override
    public CandidateSourceType sourceType() {
        return CandidateSourceType.WEAK_TIE_EXPLORATION;
    }

    @Override
    public List<RetrievedCandidate> retrieve(UUID profileId, int limit) {
        return retrievalRepository.weakTieExploration(profileId, limit);
    }
}
