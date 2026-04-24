package com.matchgraph.api.retrieval;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class ColdStartCandidateSource implements CandidateSource {

    private final RetrievalRepository retrievalRepository;

    public ColdStartCandidateSource(RetrievalRepository retrievalRepository) {
        this.retrievalRepository = retrievalRepository;
    }

    @Override
    public CandidateSourceType sourceType() {
        return CandidateSourceType.COLD_START;
    }

    @Override
    public List<RetrievedCandidate> retrieve(UUID profileId, int limit) {
        return retrievalRepository.coldStart(profileId, limit);
    }
}
