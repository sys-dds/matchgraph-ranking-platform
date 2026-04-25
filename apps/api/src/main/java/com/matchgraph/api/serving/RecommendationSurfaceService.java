package com.matchgraph.api.serving;

import java.util.List;
import java.util.Map;

import com.matchgraph.api.serving.ServingModels.RecommendationSurfaceRequest;
import com.matchgraph.api.serving.ServingModels.SurfaceConfig;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationSurfaceService {

    private static final List<String> DEFAULT_SOURCES = List.of("GRAPH_TWO_HOP", "GRAPH_MUTUALS", "VECTOR_SIMILARITY", "LOCATION_NEARBY", "WEAK_TIE_EXPLORATION", "LONG_TAIL", "COLD_START", "RECONNECT");

    private final RecommendationSurfaceRepository repository;

    public RecommendationSurfaceService(RecommendationSurfaceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public SurfaceConfig create(RecommendationSurfaceRequest request) {
        if (request == null || request.surfaceKey() == null || request.surfaceKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "surfaceKey is required");
        }
        SurfaceConfig config = new SurfaceConfig(
            request.surfaceKey().trim(),
            request.status() == null ? "ENABLED" : request.status(),
            request.rankingVersion() == null ? "v1_balanced" : request.rankingVersion(),
            request.allowedSources() == null || request.allowedSources().isEmpty() ? DEFAULT_SOURCES : request.allowedSources(),
            request.resultSize() == null ? 20 : Math.max(1, request.resultSize()),
            request.latencyBudgetMs() == null ? 250 : Math.max(1, request.latencyBudgetMs()),
            request.freshnessConfig() == null ? Map.of() : request.freshnessConfig(),
            request.diversityConfig() == null ? Map.of("maxSameSourceTopK", 2, "minSourceDiversity", 2) : request.diversityConfig(),
            request.fallbackConfig() == null ? Map.of("ruleRankingVersion", "v1_balanced") : request.fallbackConfig(),
            request.safetyConfig() == null ? Map.of("hardExclusions", "ALWAYS") : request.safetyConfig()
        );
        repository.upsertSurface(config);
        return get(config.surfaceKey());
    }

    public SurfaceConfig get(String surfaceKey) {
        seedDefaults();
        return repository.surface(surfaceKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "recommendation surface not found"));
    }

    private void seedDefaults() {
        for (String surface : List.of("HOME_FEED", "DISCOVERY", "SIMILAR_PROFILES", "RECONNECT", "NEW_USER_ONBOARDING", "TRENDING_NEARBY")) {
            if (repository.surface(surface).isEmpty()) {
                repository.upsertSurface(new SurfaceConfig(surface, "ENABLED", "v1_balanced", DEFAULT_SOURCES, 20, 250, Map.of(), Map.of("maxSameSourceTopK", 2), Map.of("ruleRankingVersion", "v1_balanced"), Map.of("hardExclusions", "ALWAYS")));
            }
        }
    }
}
