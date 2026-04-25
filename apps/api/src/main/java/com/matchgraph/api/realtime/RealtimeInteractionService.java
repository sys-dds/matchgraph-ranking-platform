package com.matchgraph.api.realtime;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionEvent;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionRequest;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RealtimeInteractionService {

    private static final Set<String> EVENT_TYPES = Set.of(
        "PROFILE_VIEW",
        "LIKE",
        "PASS",
        "BLOCK",
        "REPORT",
        "MATCH_CREATED",
        "FEED_DISMISS",
        "SOURCE_NEGATIVE",
        "SOURCE_POSITIVE"
    );
    private static final List<String> HARD_TARGETS = List.of("CURRENT_FEED", "CACHE", "CANDIDATE_POOL", "PRE_RANK", "SLATE", "DELTA_REFRESH", "FUTURE_SESSION");
    private static final List<String> SESSION_TARGETS = List.of("CURRENT_FEED", "PRE_RANK", "SLATE", "DELTA_REFRESH", "FUTURE_SESSION");

    private final RealtimeInteractionRepository repository;
    private final ProfileService profileService;

    public RealtimeInteractionService(RealtimeInteractionRepository repository, ProfileService profileService) {
        this.repository = repository;
        this.profileService = profileService;
    }

    @Transactional
    public RealtimeInteractionResponse ingest(RealtimeInteractionRequest request) {
        if (request.eventKey() == null || request.eventKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventKey is required");
        }
        if (request.profileId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is required");
        }
        String eventType = normalizeEventType(request.eventType());
        profileService.requireExists(request.profileId());
        if (request.candidateProfileId() != null) {
            profileService.requireExists(request.candidateProfileId());
        }

        var existing = repository.findByEventKey(request.eventKey());
        if (existing.isPresent()) {
            repository.incrementDuplicate(request.eventKey());
            return new RealtimeInteractionResponse(existing.get(), true, List.of("duplicate eventKey returned existing event; side effects skipped"));
        }

        RealtimeInteractionEvent event = repository.insert(
            UUID.randomUUID(),
            request.eventKey().trim(),
            request.profileId(),
            request.candidateProfileId(),
            request.feedSnapshotId(),
            request.feedItemId(),
            request.servingRequestId(),
            request.sessionId(),
            eventType,
            normalizeSource(request.sourceKey()),
            request.occurredAt() == null ? OffsetDateTime.now() : request.occurredAt(),
            request.metadata() == null ? Map.of() : request.metadata()
        );
        List<String> sideEffects = process(event);
        repository.markProcessed(event.id());
        RealtimeInteractionEvent processed = repository.find(event.id()).orElse(event);
        return new RealtimeInteractionResponse(processed, false, sideEffects);
    }

    public RealtimeInteractionEvent get(UUID eventId) {
        return repository.find(eventId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "realtime interaction event not found"));
    }

    public List<RealtimeInteractionEvent> list(UUID profileId) {
        profileService.requireExists(profileId);
        return repository.listByProfile(profileId);
    }

    private List<String> process(RealtimeInteractionEvent event) {
        List<String> sideEffects = new ArrayList<>();
        switch (event.eventType()) {
            case "BLOCK" -> {
                repository.insertInvalidation(event.profileId(), event.candidateProfileId(), event.id(), "BLOCKED", true, HARD_TARGETS, Map.of("source", "realtime_intake"));
                sideEffects.add("hard invalidation created for BLOCK");
            }
            case "REPORT" -> {
                repository.insertInvalidation(event.profileId(), event.candidateProfileId(), event.id(), "REPORTED", true, HARD_TARGETS, Map.of("source", "realtime_intake"));
                sideEffects.add("hard invalidation created for REPORT");
            }
            case "PASS" -> {
                repository.insertInvalidation(event.profileId(), event.candidateProfileId(), event.id(), "PASSED", false, SESSION_TARGETS, Map.of("source", "realtime_intake"));
                sideEffects.add("session/feed invalidation created for PASS");
            }
            case "FEED_DISMISS" -> {
                repository.insertInvalidation(event.profileId(), event.candidateProfileId(), event.id(), "FEED_DISMISSED", false, SESSION_TARGETS, Map.of("source", "realtime_intake"));
                sideEffects.add("session/feed invalidation created for FEED_DISMISS");
            }
            case "SOURCE_NEGATIVE" -> {
                repository.insertInvalidation(event.profileId(), event.candidateProfileId(), event.id(), "SOURCE_NEGATIVE", false, SESSION_TARGETS, Map.of("sourceKey", event.sourceKey() == null ? "" : event.sourceKey()));
                repository.insertSourceSignal(event.profileId(), event.sessionId(), event.sourceKey(), "SOURCE_NEGATIVE", BigDecimal.valueOf(-1), Map.of());
                sideEffects.add("negative source feedback signal created");
            }
            case "LIKE", "PROFILE_VIEW", "SOURCE_POSITIVE", "MATCH_CREATED" -> {
                BigDecimal value = "PROFILE_VIEW".equals(event.eventType()) ? new BigDecimal("0.25") : BigDecimal.ONE;
                repository.insertSourceSignal(event.profileId(), event.sessionId(), event.sourceKey(), event.eventType(), value, Map.of());
                sideEffects.add("nearline materialization/source feedback signal created");
            }
            default -> sideEffects.add("event persisted without immediate side effect");
        }
        return sideEffects;
    }

    private String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventType is required");
        }
        String normalized = eventType.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!EVENT_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported realtime eventType");
        }
        return normalized;
    }

    private String normalizeSource(String sourceKey) {
        return sourceKey == null || sourceKey.isBlank() ? null : sourceKey.trim().toUpperCase(Locale.ROOT);
    }
}
