package com.matchgraph.api.interaction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.graph.GraphEdgeService;
import com.matchgraph.api.profile.ProfileService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InteractionService {

    private static final Set<String> EVENT_TYPES = Set.of(
        "IMPRESSION",
        "PROFILE_VIEW",
        "SKIP",
        "LIKE",
        "PASS",
        "BLOCK",
        "REPORT"
    );

    private final InteractionRepository interactionRepository;
    private final ProfileService profileService;
    private final GraphEdgeService graphEdgeService;

    public InteractionService(InteractionRepository interactionRepository, ProfileService profileService, GraphEdgeService graphEdgeService) {
        this.interactionRepository = interactionRepository;
        this.profileService = profileService;
        this.graphEdgeService = graphEdgeService;
    }

    @Transactional
    public InteractionResponse record(UUID actorProfileId, RecordInteractionRequest request) {
        validate(actorProfileId, request);
        return interactionRepository.findByClientEventId(actorProfileId, request.clientEventId().trim())
            .orElseGet(() -> {
                if ("BLOCK".equals(request.eventType())) {
                    graphEdgeService.block(actorProfileId, new GraphActionRequest(request.targetProfileId(), "Interaction BLOCK"));
                }
                if ("REPORT".equals(request.eventType())) {
                    graphEdgeService.report(actorProfileId, new GraphActionRequest(request.targetProfileId(), "Interaction REPORT"));
                }
                return interactionRepository.create(UUID.randomUUID(), actorProfileId, normalize(request));
            });
    }

    public List<InteractionResponse> recent(UUID actorProfileId, Integer limit) {
        profileService.requireExists(actorProfileId);
        return interactionRepository.recent(actorProfileId, sanitizeLimit(limit));
    }

    private void validate(UUID actorProfileId, RecordInteractionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "interaction request is required");
        }
        requireText(request.clientEventId(), "clientEventId is required");
        if (request.targetProfileId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetProfileId is required");
        }
        if (actorProfileId.equals(request.targetProfileId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "self interactions are not allowed");
        }
        if (request.eventType() == null || !EVENT_TYPES.contains(request.eventType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid eventType");
        }
        if (request.feedPosition() != null && request.feedPosition() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "feedPosition must be positive");
        }
        profileService.requireExists(actorProfileId);
        profileService.requireExists(request.targetProfileId());
    }

    private RecordInteractionRequest normalize(RecordInteractionRequest request) {
        return new RecordInteractionRequest(
            request.clientEventId(),
            request.targetProfileId(),
            request.eventType(),
            request.occurredAt() == null ? OffsetDateTime.now() : request.occurredAt(),
            request.requestId(),
            request.retrievalRunId(),
            request.candidateSource(),
            request.rankingVersion(),
            request.experimentId(),
            request.variant(),
            request.feedPosition(),
            request.metadata()
        );
    }

    private int sanitizeLimit(Integer limit) {
        if (limit == null) {
            return 50;
        }
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        return limit;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
