package com.matchgraph.api.streaming;

import java.util.List;
import java.util.UUID;

import com.matchgraph.api.streaming.StreamingModels.LiveQualityAnomaly;
import com.matchgraph.api.streaming.StreamingModels.LiveQualityAnomalyRun;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/live-quality/anomalies")
public class LiveQualityAnomalyController {

    private final LiveQualityAnomalyService service;

    public LiveQualityAnomalyController(LiveQualityAnomalyService service) {
        this.service = service;
    }

    @PostMapping("/detect")
    public LiveQualityAnomalyRun detect() {
        return service.detect();
    }

    @GetMapping("/runs/{runId}")
    public LiveQualityAnomalyRun run(@PathVariable UUID runId) {
        return service.run(runId);
    }

    @GetMapping
    public List<LiveQualityAnomaly> list() {
        return service.list();
    }
}
