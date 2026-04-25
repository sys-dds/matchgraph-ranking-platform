package com.matchgraph.api.serving;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

@Repository
public class ServingQualityRepository {

    private final RecommendationSurfaceRepository repository;

    public ServingQualityRepository(RecommendationSurfaceRepository repository) {
        this.repository = repository;
    }

    public void record(UUID requestId, boolean degraded, int fallbackCount, int timeoutCount, int partialResultCount, List<String> warnings) {
        repository.insertServingQuality(requestId, degraded, fallbackCount, timeoutCount, partialResultCount, warnings);
    }
}
