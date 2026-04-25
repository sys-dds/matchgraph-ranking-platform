package com.matchgraph.api.reward;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rewards")
public class RewardController {

    private final LongTermRewardService service;

    public RewardController(LongTermRewardService service) {
        this.service = service;
    }

    @PostMapping("/long-term/runs")
    public LongTermRewardRun create(@RequestBody LongTermRewardRequest request) {
        return service.create(request);
    }

    @GetMapping("/long-term/runs/{runId}")
    public LongTermRewardRun get(@PathVariable UUID runId) {
        return service.get(runId);
    }

    @GetMapping("/long-term/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }
}
