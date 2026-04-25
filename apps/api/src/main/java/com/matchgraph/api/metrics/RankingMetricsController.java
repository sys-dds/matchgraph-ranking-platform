package com.matchgraph.api.metrics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metrics/ranking")
public class RankingMetricsController {

    private final RankingMetricsService rankingMetricsService;

    public RankingMetricsController(RankingMetricsService rankingMetricsService) {
        this.rankingMetricsService = rankingMetricsService;
    }

    @PostMapping("/ingest")
    public RankingMetricsIngestResponse ingest() {
        return rankingMetricsService.ingest();
    }

    @GetMapping("/summary")
    public RankingMetricsSummaryResponse summary() {
        return rankingMetricsService.summary();
    }
}
