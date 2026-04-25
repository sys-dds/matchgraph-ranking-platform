package com.matchgraph.api.bandit;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BanditRewardService {

    private final BanditPolicyRepository policyRepository;
    private final BanditDecisionRepository decisionRepository;
    private final BanditRewardRepository rewardRepository;

    public BanditRewardService(
        BanditPolicyRepository policyRepository,
        BanditDecisionRepository decisionRepository,
        BanditRewardRepository rewardRepository
    ) {
        this.policyRepository = policyRepository;
        this.decisionRepository = decisionRepository;
        this.rewardRepository = rewardRepository;
    }

    @Transactional
    public BanditReward reward(BanditRewardRequest request) {
        if (request == null || request.rewardEventType() == null || request.rewardEventType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rewardEventType is required");
        }
        BanditDecision decision = request.decisionId() == null
            ? null
            : decisionRepository.findDecision(request.decisionId()).orElse(null);
        BanditPolicy policy = decision == null
            ? policyRepository.findPolicy(request.policyKey()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "bandit policy not found"))
            : policyRepository.findPolicy(decision.policyId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "bandit policy not found"));
        BigDecimal value = request.rewardValue() == null
            ? defaultReward(policy.rewardConfig(), request.rewardEventType())
            : request.rewardValue();
        UUID armId = decision == null ? null : decision.armId();
        UUID rewardId = rewardRepository.insertReward(
            policy.id(),
            armId,
            request.decisionId(),
            request.profileId() == null && decision != null ? decision.profileId() : request.profileId(),
            request.candidateProfileId() == null && decision != null ? decision.candidateProfileId() : request.candidateProfileId(),
            request.rewardEventType(),
            value,
            request.interactionEventId()
        );
        if (armId != null) {
            policyRepository.incrementReward(policy.id(), armId, decision.contextSegment(), value);
        }
        return rewardRepository.findReward(rewardId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "bandit reward was not persisted"));
    }

    private BigDecimal defaultReward(Map<String, Object> config, String eventType) {
        Object configured = config == null ? null : config.get(eventType);
        if (configured != null) {
            return new BigDecimal(String.valueOf(configured));
        }
        return switch (eventType) {
            case "PROFILE_VIEW" -> BigDecimal.valueOf(0.25);
            case "LIKE" -> BigDecimal.ONE;
            case "MATCH_CREATED" -> BigDecimal.valueOf(2);
            case "PASS" -> BigDecimal.valueOf(-0.25);
            case "BLOCK", "REPORT" -> BigDecimal.valueOf(-2);
            default -> BigDecimal.ZERO;
        };
    }
}
