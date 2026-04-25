package com.matchgraph.api.bandit;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.retrieval.HardExclusionService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BanditDecisionService {

    private final BanditPolicyRepository policyRepository;
    private final BanditDecisionRepository decisionRepository;
    private final HardExclusionService hardExclusionService;

    public BanditDecisionService(
        BanditPolicyRepository policyRepository,
        BanditDecisionRepository decisionRepository,
        HardExclusionService hardExclusionService
    ) {
        this.policyRepository = policyRepository;
        this.decisionRepository = decisionRepository;
        this.hardExclusionService = hardExclusionService;
    }

    @Transactional
    public BanditDecision decide(UUID profileId, String policyKey, BanditDecisionRequest request) {
        BanditPolicy policy = policyRepository.findPolicy(policyKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "bandit policy not found"));
        if (!"ACTIVE".equals(policy.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bandit policy must be ACTIVE");
        }
        if (!"EPSILON_GREEDY".equals(policy.algorithm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only EPSILON_GREEDY is implemented");
        }
        List<BanditArm> arms = policy.arms();
        if (arms.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bandit policy must have at least one arm");
        }
        UUID candidate = safeCandidate(profileId, request);
        String contextSegment = contextSegment(request);
        BanditArm arm = chooseArm(policy, arms, contextSegment, profileId, candidate);
        boolean applyToRanking = request != null && request.applyToRanking();
        Map<String, Object> systemContext = new LinkedHashMap<>();
        systemContext.put("requestedCandidateCount", request == null || request.candidateProfileIds() == null ? 0 : request.candidateProfileIds().size());
        systemContext.put("applyToRanking", applyToRanking);
        systemContext.put("applyToRankingMode", applyToRanking ? "DECISION_ONLY" : "NONE");
        systemContext.put("feedRankingIntegration", applyToRanking ? "DECISION_ONLY" : "NONE");
        systemContext.put("HARD_EXCLUSIONS_ENFORCED", true);
        systemContext.put("safetyConstraints", "HARD_EXCLUSIONS_ENFORCED");
        systemContext.put("selectedArmKey", arm.armKey());
        systemContext.put("selectedArmStrategy", arm.strategy());
        systemContext.put("requestedCandidateProfileId", candidate == null ? null : candidate.toString());
        systemContext.put("contextSegment", contextSegment);
        Map<String, Object> context = new LinkedHashMap<>();
        if (request != null && request.decisionContext() != null) {
            context.putAll(request.decisionContext());
        }
        context.putAll(systemContext);
        UUID decisionId = decisionRepository.insertDecision(
            policy.id(),
            arm.id(),
            profileId,
            candidate,
            contextSegment,
            context,
            arm.armKey(),
            selectionReason(policy, arm, contextSegment),
            true
        );
        policyRepository.incrementDecision(policy.id(), arm.id(), contextSegment);
        return decisionRepository.findDecision(decisionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "bandit decision was not persisted"));
    }

    private UUID safeCandidate(UUID profileId, BanditDecisionRequest request) {
        UUID candidate = request == null ? null : request.candidateProfileId();
        if (candidate == null && request != null && request.candidateProfileIds() != null) {
            candidate = request.candidateProfileIds().stream()
                .filter(id -> hardExclusionService.exclusionReason(profileId, id).isEmpty())
                .findFirst()
                .orElse(null);
        }
        if (candidate != null && hardExclusionService.exclusionReason(profileId, candidate).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bandit decision cannot select a hard-excluded candidate");
        }
        return candidate;
    }

    private BanditArm chooseArm(BanditPolicy policy, List<BanditArm> arms, String contextSegment, UUID profileId, UUID candidate) {
        BigDecimal epsilon = policy.epsilon() == null ? BigDecimal.valueOf(0.10) : policy.epsilon();
        double bucket = deterministicBucket(policy.policyKey(), contextSegment, profileId, candidate);
        if (bucket < epsilon.doubleValue()) {
            int index = (int) Math.floor(deterministicBucket("explore:" + policy.policyKey(), contextSegment, profileId, candidate) * arms.size());
            return arms.get(Math.min(index, arms.size() - 1));
        }
        return arms.stream()
            .max(Comparator
                .comparing((BanditArm arm) -> policyRepository.stats(policy.id(), arm.id(), contextSegment)
                    .map(BanditArmStats::averageReward)
                    .orElse(BigDecimal.ZERO))
                .thenComparing(BanditArm::armKey))
            .orElse(arms.getFirst());
    }

    private double deterministicBucket(String key, String contextSegment, UUID profileId, UUID candidate) {
        int hash = java.util.Arrays.hashCode((key + ":" + contextSegment + ":" + profileId + ":" + candidate).getBytes(StandardCharsets.UTF_8));
        return (hash & 0x7fffffff) / (double) Integer.MAX_VALUE;
    }

    private String contextSegment(BanditDecisionRequest request) {
        if (request != null && request.contextSegment() != null && !request.contextSegment().isBlank()) {
            return switch (request.contextSegment().trim()) {
                case "new_user", "active_user", "sparse_graph", "dense_graph", "stale_embedding", "location_available", "default" -> request.contextSegment().trim();
                default -> "default";
            };
        }
        return "default";
    }

    private String selectionReason(BanditPolicy policy, BanditArm arm, String contextSegment) {
        return "EPSILON_GREEDY selected " + arm.armKey() + " for " + contextSegment + " with epsilon " + policy.epsilon();
    }
}
