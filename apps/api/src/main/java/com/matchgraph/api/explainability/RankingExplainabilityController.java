package com.matchgraph.api.explainability;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RankingExplainabilityController {

    private final RankingExplainabilityService service;

    public RankingExplainabilityController(RankingExplainabilityService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/profiles/{profileId}/candidates/{candidateProfileId}/explanation")
    public CandidateExplanation whyShown(@PathVariable UUID profileId, @PathVariable UUID candidateProfileId) {
        return service.whyShown(profileId, candidateProfileId);
    }

    @GetMapping("/api/v1/profiles/{profileId}/candidates/{candidateProfileId}/why-hidden")
    public CandidateExplanation whyHidden(@PathVariable UUID profileId, @PathVariable UUID candidateProfileId) {
        return service.whyHidden(profileId, candidateProfileId);
    }

    @GetMapping("/api/v1/ranking-decisions/{decisionLogId}/items/{candidateProfileId}/explanation")
    public CandidateExplanation decisionItem(@PathVariable UUID decisionLogId, @PathVariable UUID candidateProfileId) {
        return service.decisionItem(decisionLogId, candidateProfileId);
    }

    @PostMapping("/api/v1/explainability/ranking")
    public CandidateExplanation explain(@RequestBody RankingExplanationRequest request) {
        return service.explain(request);
    }
}
