package com.matchgraph.api.interaction;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.feed.RankableItemService;
import com.matchgraph.api.graph.GraphEdgeService;
import com.matchgraph.api.profile.ProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InteractionService {

    private static final Set<String> INTERACTION_TYPES = Set.of("VIEW", "LIKE", "DISLIKE", "SAVE", "CLICK", "HIDE");

    private final InteractionRepository interactionRepository;
    private final ProfileService profileService;
    private final RankableItemService itemService;
    private final GraphEdgeService graphEdgeService;
    private final ObjectMapper objectMapper;

    public InteractionService(
        InteractionRepository interactionRepository,
        ProfileService profileService,
        RankableItemService itemService,
        GraphEdgeService graphEdgeService,
        ObjectMapper objectMapper
    ) {
        this.interactionRepository = interactionRepository;
        this.profileService = profileService;
        this.itemService = itemService;
        this.graphEdgeService = graphEdgeService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public InteractionResponse record(RecordInteractionRequest request) {
        if (request.profileId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is required");
        }
        if (request.itemId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itemId is required");
        }
        if (request.interactionType() == null || !INTERACTION_TYPES.contains(request.interactionType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid interactionType");
        }

        profileService.requireExists(request.profileId());
        itemService.requireExists(request.itemId());

        InteractionResponse interaction = interactionRepository.create(
            UUID.randomUUID(),
            request.profileId(),
            request.itemId(),
            request.interactionType(),
            metadataJson(request.metadata())
        );
        graphEdgeService.recordInteractionSignal(request.profileId(), request.itemId(), request.interactionType());
        return interaction;
    }

    private String metadataJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metadata must be valid JSON");
        }
    }
}
