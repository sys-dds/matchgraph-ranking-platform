package com.matchgraph.api.retrieval;

import java.util.Optional;
import java.util.UUID;

import com.matchgraph.api.graph.GraphEdgeService;

import org.springframework.stereotype.Service;

@Service
public class HardExclusionService {

    private final GraphEdgeService graphEdgeService;
    private final RetrievalRepository retrievalRepository;

    public HardExclusionService(GraphEdgeService graphEdgeService, RetrievalRepository retrievalRepository) {
        this.graphEdgeService = graphEdgeService;
        this.retrievalRepository = retrievalRepository;
    }

    public Optional<String> exclusionReason(UUID actorProfileId, UUID candidateProfileId) {
        if (actorProfileId.equals(candidateProfileId)) {
            return Optional.of("SELF");
        }
        if (!"ACTIVE".equals(retrievalRepository.profileStatus(candidateProfileId))) {
            return Optional.of("INACTIVE_PROFILE");
        }
        if (graphEdgeService.blockedEitherDirection(actorProfileId, candidateProfileId)) {
            return Optional.of("BLOCKED_EITHER_DIRECTION");
        }
        if (retrievalRepository.safetyBlocked(candidateProfileId) || retrievalRepository.activeOutgoingEdge(actorProfileId, candidateProfileId, "MUTE")) {
            return Optional.of("SUPPRESSED_PROFILE");
        }
        if (retrievalRepository.activeOutgoingEdge(actorProfileId, candidateProfileId, "REPORT")) {
            return Optional.of("ALREADY_REPORTED");
        }
        return Optional.empty();
    }
}
