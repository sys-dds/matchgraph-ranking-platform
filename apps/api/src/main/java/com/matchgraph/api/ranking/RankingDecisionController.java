package com.matchgraph.api.ranking;

import java.util.UUID;

import com.matchgraph.api.shared.cache.OnlineServingCacheService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ranking-decisions")
public class RankingDecisionController {

    private final RankingService rankingService;
    private final OnlineServingCacheService cacheService;

    public RankingDecisionController(RankingService rankingService, OnlineServingCacheService cacheService) {
        this.rankingService = rankingService;
        this.cacheService = cacheService;
    }

    @GetMapping("/{decisionLogId}")
    public RankingDecision get(@PathVariable UUID decisionLogId) {
        String cacheKey = cacheService.rankingDecisionKey(decisionLogId);
        return cacheService.get(cacheKey, RankingDecision.class)
            .orElseGet(() -> {
                RankingDecision decision = rankingService.get(decisionLogId);
                cacheService.putRanking(cacheKey, decision);
                return decision;
            });
    }

    @PostMapping("/{decisionLogId}/replay")
    public RankingReplayResponse replay(@PathVariable UUID decisionLogId) {
        String cacheKey = cacheService.rankingReplayKey(decisionLogId);
        return cacheService.get(cacheKey, RankingReplayResponse.class)
            .orElseGet(() -> {
                RankingReplayResponse replay = rankingService.replay(decisionLogId);
                cacheService.putRanking(cacheKey, replay);
                return replay;
            });
    }
}
