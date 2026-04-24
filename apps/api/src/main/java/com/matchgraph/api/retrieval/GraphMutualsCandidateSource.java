package com.matchgraph.api.retrieval;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class GraphMutualsCandidateSource implements CandidateSource {

    private final RetrievalRepository retrievalRepository;

    public GraphMutualsCandidateSource(RetrievalRepository retrievalRepository) {
        this.retrievalRepository = retrievalRepository;
    }

    @Override
    public CandidateSourceType sourceType() {
        return CandidateSourceType.GRAPH_MUTUALS;
    }

    @Override
    public List<RetrievedCandidate> retrieve(UUID profileId, int limit) {
        return retrievalRepository.graphMutuals(profileId, limit);
    }
}
