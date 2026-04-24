package com.matchgraph.api.retrieval;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class GraphTwoHopCandidateSource implements CandidateSource {

    private final RetrievalRepository retrievalRepository;

    public GraphTwoHopCandidateSource(RetrievalRepository retrievalRepository) {
        this.retrievalRepository = retrievalRepository;
    }

    @Override
    public CandidateSourceType sourceType() {
        return CandidateSourceType.GRAPH_TWO_HOP;
    }

    @Override
    public List<RetrievedCandidate> retrieve(UUID profileId, int limit) {
        return retrievalRepository.graphTwoHop(profileId, limit);
    }
}
