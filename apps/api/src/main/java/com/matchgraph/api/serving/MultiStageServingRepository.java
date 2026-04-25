package com.matchgraph.api.serving;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

@Repository
public class MultiStageServingRepository {

    private final RecommendationSurfaceRepository repository;

    public MultiStageServingRepository(RecommendationSurfaceRepository repository) {
        this.repository = repository;
    }

    public Optional<Map<String, Object>> trace(UUID requestId) {
        return repository.trace(requestId);
    }
}
