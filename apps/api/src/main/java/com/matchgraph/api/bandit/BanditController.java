package com.matchgraph.api.bandit;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BanditController {

    private final BanditPolicyService policyService;
    private final BanditDecisionService decisionService;
    private final BanditRewardService rewardService;

    public BanditController(
        BanditPolicyService policyService,
        BanditDecisionService decisionService,
        BanditRewardService rewardService
    ) {
        this.policyService = policyService;
        this.decisionService = decisionService;
        this.rewardService = rewardService;
    }

    @PostMapping("/api/v1/bandits/policies")
    public BanditPolicy create(@RequestBody BanditPolicyRequest request) {
        return policyService.create(request);
    }

    @GetMapping("/api/v1/bandits/policies/{policyKey}")
    public BanditPolicy get(@PathVariable String policyKey) {
        return policyService.get(policyKey);
    }

    @PostMapping("/api/v1/profiles/{profileId}/bandits/{policyKey}/decide")
    public BanditDecision decide(@PathVariable UUID profileId, @PathVariable String policyKey, @RequestBody BanditDecisionRequest request) {
        return decisionService.decide(profileId, policyKey, request);
    }

    @PostMapping("/api/v1/bandits/rewards")
    public BanditReward reward(@RequestBody BanditRewardRequest request) {
        return rewardService.reward(request);
    }

    @GetMapping("/api/v1/bandits/policies/{policyKey}/summary")
    public Map<String, Object> summary(@PathVariable String policyKey) {
        return policyService.summary(policyKey);
    }
}
