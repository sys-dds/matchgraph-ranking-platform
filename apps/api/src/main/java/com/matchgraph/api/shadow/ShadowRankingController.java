package com.matchgraph.api.shadow;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ranking")
public class ShadowRankingController {

    private final ShadowRankingService shadowRankingService;
    private final ChampionChallengerService championChallengerService;

    public ShadowRankingController(
        ShadowRankingService shadowRankingService,
        ChampionChallengerService championChallengerService
    ) {
        this.shadowRankingService = shadowRankingService;
        this.championChallengerService = championChallengerService;
    }

    @PostMapping("/shadow/run")
    public ShadowRankingRun runShadow(@RequestBody ShadowRankingRunRequest request) {
        return shadowRankingService.run(request);
    }

    @GetMapping("/shadow/runs/{shadowRunId}")
    public ShadowRankingRun getShadow(@PathVariable UUID shadowRunId) {
        return shadowRankingService.get(shadowRunId);
    }

    @PostMapping("/champion-challenger/configs")
    public ChampionChallengerConfig createConfig(@RequestBody ChampionChallengerConfigRequest request) {
        return championChallengerService.create(request);
    }

    @GetMapping("/champion-challenger/configs/{configKey}")
    public ChampionChallengerConfig getConfig(@PathVariable String configKey) {
        return championChallengerService.get(configKey);
    }

    @PostMapping("/champion-challenger/configs/{configKey}/evaluate")
    public ChampionChallengerDecision evaluate(@PathVariable String configKey, @RequestBody ChampionChallengerEvaluateRequest request) {
        return championChallengerService.evaluate(configKey, request);
    }
}
