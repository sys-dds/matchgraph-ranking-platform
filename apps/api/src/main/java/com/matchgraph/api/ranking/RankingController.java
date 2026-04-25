package com.matchgraph.api.ranking;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles/{profileId}/ranking")
public class RankingController {

    private final RankingService rankingService;

    public RankingController(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @PostMapping("/run")
    public RankingDecision run(@PathVariable UUID profileId, @RequestBody RankingRunRequest request) {
        return rankingService.run(profileId, request.featureSnapshotRunId(), request.rankingVersion(), request.limit(), "RANKING_RUN");
    }

    @GetMapping("/decisions/{decisionLogId}")
    public RankingDecision get(@PathVariable UUID profileId, @PathVariable UUID decisionLogId) {
        return rankingService.get(profileId, decisionLogId);
    }
}
