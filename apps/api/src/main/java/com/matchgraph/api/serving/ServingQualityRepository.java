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

    public UUID record(UUID requestId, boolean degraded, int fallbackCount, int timeoutCount, int partialResultCount, List<String> warnings) {
        return repository.insertServingQuality(requestId, degraded, fallbackCount, timeoutCount, partialResultCount, warnings);
    }
}
