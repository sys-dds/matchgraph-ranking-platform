package com.matchgraph.api.serving;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class ServingQualityService {

    private final ServingQualityRepository repository;

    public ServingQualityService(ServingQualityRepository repository) {
        this.repository = repository;
    }

    public void record(UUID requestId, boolean degraded, int fallbackCount, int timeoutCount, int partialResultCount, List<String> warnings) {
        repository.record(requestId, degraded, fallbackCount, timeoutCount, partialResultCount, warnings);
    }
}
