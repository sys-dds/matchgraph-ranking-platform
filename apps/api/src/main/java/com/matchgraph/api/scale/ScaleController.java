package com.matchgraph.api.scale;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scale")
public class ScaleController {

    private final ScaleSeedService seedService;
    private final ScaleBenchmarkService benchmarkService;

    public ScaleController(ScaleSeedService seedService, ScaleBenchmarkService benchmarkService) {
        this.seedService = seedService;
        this.benchmarkService = benchmarkService;
    }

    @PostMapping("/seed")
    public ScaleSeedRun seed(@RequestBody(required = false) ScaleSeedRequest request) {
        return seedService.seed(request);
    }

    @GetMapping("/seed/{seedRunId}")
    public ScaleSeedRun seedRun(@PathVariable UUID seedRunId) {
        return seedService.get(seedRunId);
    }

    @PostMapping("/benchmark/ranking")
    public RankingBenchmarkResponse benchmark(@RequestBody(required = false) RankingBenchmarkRequest request) {
        return benchmarkService.benchmark(request);
    }

    @GetMapping("/benchmark/ranking/{benchmarkRunId}")
    public RankingBenchmarkResponse benchmarkRun(@PathVariable UUID benchmarkRunId) {
        return benchmarkService.get(benchmarkRunId);
    }
}
