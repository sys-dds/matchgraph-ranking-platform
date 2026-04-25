package com.matchgraph.api.realtime;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.FeatureFreshnessCheck;
import com.matchgraph.api.realtime.RealtimeModels.FeatureFreshnessCheckRequest;
import com.matchgraph.api.realtime.RealtimeModels.FeatureFreshnessResult;
import com.matchgraph.api.realtime.RealtimeModels.NearlineFeatureMaterializationRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnlineFeatureFreshnessGuardService {

    private final FeatureFreshnessRepository repository;
    private final NearlineFeatureMaterializerService materializerService;

    public OnlineFeatureFreshnessGuardService(FeatureFreshnessRepository repository, NearlineFeatureMaterializerService materializerService) {
        this.repository = repository;
        this.materializerService = materializerService;
    }

    @Transactional
    public FeatureFreshnessCheck check(FeatureFreshnessCheckRequest request) {
        long maxAgeMs = request.maxAgeMs() == null ? 300_000 : request.maxAgeMs();
        boolean allowRebuild = !Boolean.FALSE.equals(request.allowRebuild());
        boolean allowFallback = !Boolean.FALSE.equals(request.allowFallback());
        List<String> keys = request.requiredFeatureKeys() == null || request.requiredFeatureKeys().isEmpty()
            ? List.of("views_5m", "likes_5m", "recent_affinity_score")
            : request.requiredFeatureKeys();
        List<FeatureFreshnessResult> results = new ArrayList<>();
        boolean degraded = false;
        boolean rebuilt = false;
        for (String key : keys) {
            var last = repository.lastMaterialized(request.profileId(), request.candidateProfileId(), key);
            if (last.isEmpty()) {
                if (allowRebuild) {
                    materializerService.materialize(new NearlineFeatureMaterializationRequest(request.profileId(), request.candidateProfileId()));
                    rebuilt = true;
                    results.add(new FeatureFreshnessResult(key, request.profileId(), request.candidateProfileId(), null, maxAgeMs, "REBUILT", true, false, Map.of("reason", "missing required feature rebuilt from durable events")));
                } else {
                    degraded = true;
                    results.add(new FeatureFreshnessResult(key, request.profileId(), request.candidateProfileId(), null, maxAgeMs, "MISSING", true, allowFallback, Map.of("fallbackRequired", allowFallback)));
                }
                continue;
            }
            long ageMs = Duration.between(last.get(), OffsetDateTime.now()).toMillis();
            if (ageMs > maxAgeMs) {
                if (allowRebuild) {
                    materializerService.materialize(new NearlineFeatureMaterializationRequest(request.profileId(), request.candidateProfileId()));
                    rebuilt = true;
                    results.add(new FeatureFreshnessResult(key, request.profileId(), request.candidateProfileId(), ageMs, maxAgeMs, "REBUILT", true, false, Map.of("previousStatus", "STALE")));
                } else {
                    degraded = true;
                    results.add(new FeatureFreshnessResult(key, request.profileId(), request.candidateProfileId(), ageMs, maxAgeMs, allowFallback ? "STALE" : "DEGRADED", true, allowFallback, Map.of("fallbackRequired", allowFallback)));
                }
            } else {
                results.add(new FeatureFreshnessResult(key, request.profileId(), request.candidateProfileId(), ageMs, maxAgeMs, "FRESH", true, false, Map.of("maxAgeMs", maxAgeMs)));
            }
        }
        String status = degraded ? "DEGRADED" : rebuilt ? "REBUILT" : "FRESH";
        UUID checkId = repository.createCheck(request.profileId(), request.candidateProfileId(), allowRebuild, allowFallback, status, Map.of(
            "status", status,
            "modelBackedRankingGuard", "stale or missing required features require rebuild or fallback",
            "fallbackAllowed", allowFallback
        ));
        results.forEach(result -> repository.insertResult(checkId, result));
        return repository.get(checkId);
    }

    public FeatureFreshnessCheck get(UUID checkId) {
        return repository.get(checkId);
    }
}
