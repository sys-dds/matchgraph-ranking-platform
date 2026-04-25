package com.matchgraph.api.streaming;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.streaming.StreamingModels.CandidateFeatureWindow;
import com.matchgraph.api.streaming.StreamingModels.FeatureWindowRun;
import com.matchgraph.api.streaming.StreamingModels.ProfileFeatureWindow;
import com.matchgraph.api.streaming.StreamingModels.StreamingFeatureWindowRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StreamingFeatureWindowService {

    private final StreamingFeatureWindowRepository repository;
    private final ProfileService profileService;

    public StreamingFeatureWindowService(StreamingFeatureWindowRepository repository, ProfileService profileService) {
        this.repository = repository;
        this.profileService = profileService;
    }

    @Transactional
    public FeatureWindowRun materialize(StreamingFeatureWindowRequest request) {
        UUID profileId = request == null ? null : request.profileId();
        UUID candidateId = request == null ? null : request.candidateProfileId();
        if (profileId != null) {
            profileService.requireExists(profileId);
        }
        if (candidateId != null) {
            profileService.requireExists(candidateId);
        }
        FeatureWindowRun run = repository.createRun(true, Map.of(
            "approximate", true,
            "reason", "source/surface metrics use stored serving traces where available"
        ));
        for (String windowKey : repository.windowKeys()) {
            Duration duration = duration(windowKey);
            if (profileId != null) {
                repository.insertProfile(run.id(), profile(profileId, windowKey, duration));
            }
            if (candidateId != null) {
                repository.insertCandidate(run.id(), candidate(candidateId, windowKey, duration));
            }
            if (request != null && request.sourceKey() != null) {
                repository.insertSource(run.id(), repository.sourceAggregate(request.sourceKey(), windowKey, duration));
            }
            if (request != null && request.surfaceKey() != null) {
                repository.insertSurface(run.id(), repository.surfaceAggregate(request.surfaceKey(), windowKey, duration));
            }
        }
        return run;
    }

    public List<ProfileFeatureWindow> profileWindows(UUID profileId) {
        List<ProfileFeatureWindow> windows = new ArrayList<>();
        for (String key : repository.windowKeys()) {
            try {
                windows.add(repository.profileWindow(profileId, key));
            } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
                windows.add(profile(profileId, key, duration(key)));
            }
        }
        return windows;
    }

    public List<CandidateFeatureWindow> candidateWindows(UUID candidateId) {
        List<CandidateFeatureWindow> persisted = repository.candidateWindows(candidateId);
        if (!persisted.isEmpty()) {
            return persisted;
        }
        return repository.windowKeys().stream().map(key -> candidate(candidateId, key, duration(key))).toList();
    }

    public List<StreamingModels.SourceFeatureWindow> sourceWindows(String sourceKey) {
        List<StreamingModels.SourceFeatureWindow> persisted = repository.sourceWindows(sourceKey);
        if (!persisted.isEmpty()) {
            return persisted;
        }
        return repository.windowKeys().stream().map(key -> repository.sourceAggregate(sourceKey, key, duration(key))).toList();
    }

    public List<StreamingModels.SurfaceFeatureWindow> surfaceWindows(String surfaceKey) {
        List<StreamingModels.SurfaceFeatureWindow> persisted = repository.surfaceWindows(surfaceKey);
        if (!persisted.isEmpty()) {
            return persisted;
        }
        return repository.windowKeys().stream().map(key -> repository.surfaceAggregate(surfaceKey, key, duration(key))).toList();
    }

    private ProfileFeatureWindow profile(UUID profileId, String windowKey, Duration duration) {
        return new ProfileFeatureWindow(
            profileId,
            windowKey,
            repository.eventCount(profileId, null, "PROFILE_VIEW", duration),
            repository.eventCount(profileId, null, "LIKE", duration),
            repository.eventCount(profileId, null, "PASS", duration),
            repository.eventCount(profileId, null, "BLOCK", duration),
            repository.eventCount(profileId, null, "REPORT", duration),
            repository.eventCount(profileId, null, "FEED_DISMISS", duration),
            repository.eventCount(profileId, null, "MATCH_CREATED", duration),
            false
        );
    }

    private CandidateFeatureWindow candidate(UUID candidateId, String windowKey, Duration duration) {
        long blocks = repository.eventCount(null, candidateId, "BLOCK", duration);
        long reports = repository.eventCount(null, candidateId, "REPORT", duration);
        return new CandidateFeatureWindow(
            candidateId,
            windowKey,
            repository.eventCount(null, candidateId, "PROFILE_VIEW", duration),
            repository.eventCount(null, candidateId, "LIKE", duration),
            repository.eventCount(null, candidateId, "PASS", duration),
            blocks,
            reports,
            repository.eventCount(null, candidateId, "MATCH_CREATED", duration),
            java.math.BigDecimal.valueOf(blocks * 2L + reports * 3L),
            false
        );
    }

    private Duration duration(String key) {
        return switch (key) {
            case "1m" -> Duration.ofMinutes(1);
            case "5m" -> Duration.ofMinutes(5);
            case "1h" -> Duration.ofHours(1);
            case "24h" -> Duration.ofHours(24);
            default -> Duration.ofHours(1);
        };
    }
}
