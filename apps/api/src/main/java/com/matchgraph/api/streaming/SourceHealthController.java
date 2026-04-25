package com.matchgraph.api.streaming;

import com.matchgraph.api.streaming.StreamingModels.SourceBackpressureAction;
import com.matchgraph.api.streaming.StreamingModels.SourceHealthSnapshot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sources")
public class SourceHealthController {

    private final SourceHealthService healthService;
    private final SourceBackpressureService backpressureService;

    public SourceHealthController(SourceHealthService healthService, SourceBackpressureService backpressureService) {
        this.healthService = healthService;
        this.backpressureService = backpressureService;
    }

    @PostMapping("/health/evaluate")
    public SourceHealthSnapshot evaluate(@RequestParam String sourceKey) {
        return healthService.evaluate(sourceKey);
    }

    @GetMapping("/{sourceKey}/health")
    public SourceHealthSnapshot health(@PathVariable String sourceKey) {
        return healthService.latest(sourceKey);
    }

    @PostMapping("/{sourceKey}/backpressure")
    public SourceBackpressureAction backpressure(@PathVariable String sourceKey, @RequestParam(defaultValue = "REDUCE_BUDGET") String action, @RequestParam(defaultValue = "4") int budgetBefore, @RequestParam(required = false) Integer budgetAfter) {
        return backpressureService.apply(sourceKey, action, budgetBefore, budgetAfter);
    }

    @PostMapping("/{sourceKey}/restore")
    public SourceBackpressureAction restore(@PathVariable String sourceKey) {
        return backpressureService.restore(sourceKey);
    }
}
