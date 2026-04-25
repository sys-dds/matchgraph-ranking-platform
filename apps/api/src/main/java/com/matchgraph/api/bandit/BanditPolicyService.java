package com.matchgraph.api.bandit;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BanditPolicyService {

    private final BanditPolicyRepository repository;

    public BanditPolicyService(BanditPolicyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public BanditPolicy create(BanditPolicyRequest request) {
        validate(request);
        UUID policyId = repository.createPolicy(request);
        for (BanditArmRequest arm : request.arms()) {
            repository.createArm(policyId, arm);
        }
        return get(request.policyKey());
    }

    public BanditPolicy get(String policyKey) {
        return repository.findPolicy(policyKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "bandit policy not found"));
    }

    public Map<String, Object> summary(String policyKey) {
        BanditPolicy policy = get(policyKey);
        List<BanditArmStats> stats = repository.stats(policy.id());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("policyKey", policy.policyKey());
        summary.put("algorithm", policy.algorithm());
        summary.put("epsilon", policy.epsilon());
        summary.put("armCount", policy.arms().size());
        summary.put("stats", stats);
        summary.put("rewardSummary", "per-arm/context reward counts and averages");
        return summary;
    }

    private void validate(BanditPolicyRequest request) {
        if (request == null || request.policyKey() == null || request.policyKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "policyKey is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (request.arms() == null || request.arms().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one arm is required");
        }
        for (BanditArmRequest arm : request.arms()) {
            if (arm.armKey() == null || arm.armKey().isBlank() || arm.sourceType() == null || arm.strategy() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "armKey, sourceType, and strategy are required");
            }
        }
    }
}
