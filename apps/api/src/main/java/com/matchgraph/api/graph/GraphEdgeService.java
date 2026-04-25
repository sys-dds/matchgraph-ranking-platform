package com.matchgraph.api.graph;

import java.util.List;
import java.util.UUID;

import com.matchgraph.api.profile.ProfileRepository;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.shared.cache.OnlineServingCacheService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GraphEdgeService {

    private static final List<String> FOLLOW_MUTE = List.of("FOLLOW", "MUTE");

    private final GraphRepository graphRepository;
    private final ProfileService profileService;
    private final ProfileRepository profileRepository;
    private final OnlineServingCacheService cacheService;

    public GraphEdgeService(
        GraphRepository graphRepository,
        ProfileService profileService,
        ProfileRepository profileRepository,
        OnlineServingCacheService cacheService
    ) {
        this.graphRepository = graphRepository;
        this.profileService = profileService;
        this.profileRepository = profileRepository;
        this.cacheService = cacheService;
    }

    @Transactional
    public GraphEdgeResponse follow(UUID sourceProfileId, GraphActionRequest request) {
        rejectIfBlocked(sourceProfileId, requireTarget(request), "FOLLOW");
        return activateEdge(sourceProfileId, request, "FOLLOW");
    }

    @Transactional
    public List<GraphEdgeResponse> unfollow(UUID sourceProfileId, GraphActionRequest request) {
        return deactivate(sourceProfileId, requireTarget(request), List.of("FOLLOW"), request.reason());
    }

    @Transactional
    public GraphEdgeResponse mute(UUID sourceProfileId, GraphActionRequest request) {
        rejectIfBlocked(sourceProfileId, requireTarget(request), "MUTE");
        return activateEdge(sourceProfileId, request, "MUTE");
    }

    @Transactional
    public List<GraphEdgeResponse> unmute(UUID sourceProfileId, GraphActionRequest request) {
        return deactivate(sourceProfileId, requireTarget(request), List.of("MUTE"), request.reason());
    }

    @Transactional
    public GraphEdgeResponse block(UUID sourceProfileId, GraphActionRequest request) {
        UUID targetProfileId = requireTarget(request);
        validateProfiles(sourceProfileId, targetProfileId);
        deactivate(sourceProfileId, targetProfileId, FOLLOW_MUTE, "Blocked profile");
        deactivate(targetProfileId, sourceProfileId, FOLLOW_MUTE, "Blocked profile");
        GraphEdgeResponse edge = activateEdge(sourceProfileId, request, "BLOCK");
        invalidateVisibility(sourceProfileId, targetProfileId);
        return edge;
    }

    @Transactional
    public List<GraphEdgeResponse> unblock(UUID sourceProfileId, GraphActionRequest request) {
        return deactivate(sourceProfileId, requireTarget(request), List.of("BLOCK"), request.reason());
    }

    @Transactional
    public GraphEdgeResponse report(UUID sourceProfileId, GraphActionRequest request) {
        GraphEdgeResponse edge = activateEdge(sourceProfileId, request, "REPORT");
        profileRepository.updateSafetyState(edge.targetProfileId(), "LIMITED", "Profile reported");
        profileRepository.createSafetyEvent(UUID.randomUUID(), edge.targetProfileId(), "LIMITED", "Profile reported");
        invalidateVisibility(sourceProfileId, edge.targetProfileId());
        return edge;
    }

    public List<GraphEdgeResponse> outgoing(UUID sourceProfileId) {
        profileService.requireExists(sourceProfileId);
        return graphRepository.outgoingEdges(sourceProfileId);
    }

    public List<GraphExclusionResponse> exclusions(UUID sourceProfileId) {
        profileService.requireExists(sourceProfileId);
        return graphRepository.safetyExclusions(sourceProfileId);
    }

    public boolean blockedEitherDirection(UUID firstProfileId, UUID secondProfileId) {
        return graphRepository.activeBlockEitherDirection(firstProfileId, secondProfileId);
    }

    private GraphEdgeResponse activateEdge(UUID sourceProfileId, GraphActionRequest request, String edgeType) {
        UUID targetProfileId = requireTarget(request);
        validateProfiles(sourceProfileId, targetProfileId);
        return graphRepository.findActiveEdge(sourceProfileId, targetProfileId, edgeType)
            .map(existing -> {
                graphRepository.recordEvent(UUID.randomUUID(), sourceProfileId, targetProfileId, edgeType, "UPDATED", request.reason());
                return existing;
            })
            .orElseGet(() -> {
                GraphEdgeResponse created = graphRepository.createActiveEdge(UUID.randomUUID(), sourceProfileId, targetProfileId, edgeType, request.reason());
                graphRepository.recordEvent(UUID.randomUUID(), sourceProfileId, targetProfileId, edgeType, "CREATED", request.reason());
                return created;
            });
    }

    private void rejectIfBlocked(UUID sourceProfileId, UUID targetProfileId, String edgeType) {
        validateProfiles(sourceProfileId, targetProfileId);
        if (graphRepository.activeBlockEitherDirection(sourceProfileId, targetProfileId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "active block prevents " + edgeType.toLowerCase());
        }
    }

    private List<GraphEdgeResponse> deactivate(UUID sourceProfileId, UUID targetProfileId, List<String> edgeTypes, String reason) {
        validateProfiles(sourceProfileId, targetProfileId);
        List<GraphEdgeResponse> deactivated = graphRepository.deactivateActiveEdges(sourceProfileId, targetProfileId, edgeTypes);
        for (GraphEdgeResponse edge : deactivated) {
            graphRepository.recordEvent(UUID.randomUUID(), sourceProfileId, targetProfileId, edge.edgeType(), "DEACTIVATED", reason);
        }
        return deactivated;
    }

    private void validateProfiles(UUID sourceProfileId, UUID targetProfileId) {
        if (sourceProfileId.equals(targetProfileId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profile cannot edge itself");
        }
        profileService.requireExists(sourceProfileId);
        profileService.requireExists(targetProfileId);
    }

    private UUID requireTarget(GraphActionRequest request) {
        if (request == null || request.targetProfileId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetProfileId is required");
        }
        return request.targetProfileId();
    }

    private void invalidateVisibility(UUID sourceProfileId, UUID targetProfileId) {
        cacheService.invalidateFeed(sourceProfileId);
        cacheService.invalidateFeed(targetProfileId);
    }
}
