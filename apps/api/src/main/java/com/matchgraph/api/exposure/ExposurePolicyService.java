package com.matchgraph.api.exposure;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExposurePolicyService {

    private final ExposureRepository repository;

    public ExposurePolicyService(ExposureRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ExposureControlPolicy create(ExposurePolicyRequest request) {
        validate(request);
        repository.createPolicy(request);
        return get(request.policyKey());
    }

    public ExposureControlPolicy get(String policyKey) {
        return repository.findPolicy(policyKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "exposure policy not found"));
    }

    public Map<String, Object> summary(String policyKey) {
        ExposureControlPolicy policy = get(policyKey);
        return Map.of(
            "policyKey", policy.policyKey(),
            "status", policy.status(),
            "dailyCap", policy.dailyCap(),
            "rolling7DayCap", policy.rolling7DayCap(),
            "longTailBoost", policy.longTailBoost(),
            "overexposureDownrank", policy.overexposureDownrank(),
            "safetyOverride", "hard exclusions always win"
        );
    }

    private void validate(ExposurePolicyRequest request) {
        if (request == null || request.policyKey() == null || request.policyKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "policyKey is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
    }
}
