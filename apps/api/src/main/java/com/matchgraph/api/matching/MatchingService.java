package com.matchgraph.api.matching;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.interaction.InteractionRepository;
import com.matchgraph.api.interaction.RecordInteractionRequest;
import com.matchgraph.api.profile.ProfileService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MatchingService {

    private final MatchingRepository matchingRepository;
    private final InteractionRepository interactionRepository;
    private final ProfileService profileService;

    public MatchingService(MatchingRepository matchingRepository, InteractionRepository interactionRepository, ProfileService profileService) {
        this.matchingRepository = matchingRepository;
        this.interactionRepository = interactionRepository;
        this.profileService = profileService;
    }

    @Transactional
    public SwipeResponse swipe(UUID actorProfileId, SwipeRequest request) {
        validate(actorProfileId, request);
        matchingRepository.lockPair(actorProfileId, request.targetProfileId());
        return matchingRepository.findSwipeByClientEvent(actorProfileId, request.clientEventId().trim())
            .map(existing -> withExistingMatch(existing))
            .orElseGet(() -> createSwipe(actorProfileId, request));
    }

    public List<MatchResponse> matches(UUID profileId) {
        profileService.requireExists(profileId);
        return matchingRepository.matches(profileId);
    }

    private SwipeResponse createSwipe(UUID actorProfileId, SwipeRequest request) {
        SwipeResponse swipe = matchingRepository.createSwipe(actorProfileId, normalize(request));
        if (!"RIGHT".equals(swipe.direction()) || !matchingRepository.rightSwipePairComplete(actorProfileId, swipe.targetProfileId())) {
            return swipe;
        }
        MatchingRepository.MatchCreation matchCreation = matchingRepository.createMatchIfAbsent(actorProfileId, swipe.targetProfileId());
        if (matchCreation.created()) {
            recordMatchCreated(matchCreation.match());
        }
        return new SwipeResponse(
            swipe.id(),
            swipe.actorProfileId(),
            swipe.targetProfileId(),
            swipe.direction(),
            swipe.clientEventId(),
            swipe.createdAt(),
            false,
            matchCreation.created(),
            matchCreation.match()
        );
    }

    private SwipeResponse withExistingMatch(SwipeResponse existing) {
        MatchResponse match = matchingRepository.findMatch(existing.actorProfileId(), existing.targetProfileId()).orElse(null);
        return new SwipeResponse(
            existing.id(),
            existing.actorProfileId(),
            existing.targetProfileId(),
            existing.direction(),
            existing.clientEventId(),
            existing.createdAt(),
            true,
            false,
            match
        );
    }

    private void recordMatchCreated(MatchResponse match) {
        interactionRepository.create(
            UUID.randomUUID(),
            match.profileAId(),
            new RecordInteractionRequest(
                "match-created-" + match.id(),
                match.profileBId(),
                "MATCH_CREATED",
                OffsetDateTime.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("matchId", match.id().toString())
            )
        );
    }

    private SwipeRequest normalize(SwipeRequest request) {
        return new SwipeRequest(request.targetProfileId(), request.direction().trim().toUpperCase(), request.clientEventId().trim());
    }

    private void validate(UUID actorProfileId, SwipeRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "swipe request is required");
        }
        if (request.targetProfileId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetProfileId is required");
        }
        if (actorProfileId.equals(request.targetProfileId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "self swipe is not allowed");
        }
        if (request.clientEventId() == null || request.clientEventId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientEventId is required");
        }
        String direction = request.direction() == null ? null : request.direction().trim().toUpperCase();
        if (!"LEFT".equals(direction) && !"RIGHT".equals(direction)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction must be LEFT or RIGHT");
        }
        profileService.requireExists(actorProfileId);
        profileService.requireExists(request.targetProfileId());
        if (matchingRepository.blockedOrSuppressed(actorProfileId, request.targetProfileId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "blocked or suppressed profiles cannot match");
        }
        if (matchingRepository.safetyBlocked(actorProfileId, request.targetProfileId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "safety blocked profiles cannot match");
        }
    }
}
