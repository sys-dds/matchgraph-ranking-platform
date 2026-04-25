package com.matchgraph.api.ranking;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ranking-decisions")
public class RankingDecisionController {

    private final RankingService rankingService;

    public RankingDecisionController(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @GetMapping("/{decisionLogId}")
    public RankingDecision get(@PathVariable UUID decisionLogId) {
        return rankingService.get(decisionLogId);
    }

    @PostMapping("/{decisionLogId}/replay")
    public RankingReplayResponse replay(@PathVariable UUID decisionLogId) {
        return rankingService.replay(decisionLogId);
    }
}
