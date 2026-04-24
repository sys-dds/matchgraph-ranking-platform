package com.matchgraph.api.retrieval;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class VectorSimilarityCandidateSource implements CandidateSource {

    private final RetrievalRepository retrievalRepository;

    public VectorSimilarityCandidateSource(RetrievalRepository retrievalRepository) {
        this.retrievalRepository = retrievalRepository;
    }

    @Override
    public CandidateSourceType sourceType() {
        return CandidateSourceType.VECTOR_SIMILARITY;
    }

    @Override
    public List<RetrievedCandidate> retrieve(UUID profileId, int limit) {
        return retrievalRepository.vectorSimilarity(profileId, limit);
    }
}
