package com.matchgraph.api.evaluation;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluation/offline/ranking")
public class OfflineEvaluationController {

    private final OfflineEvaluationService offlineEvaluationService;

    public OfflineEvaluationController(OfflineEvaluationService offlineEvaluationService) {
        this.offlineEvaluationService = offlineEvaluationService;
    }

    @PostMapping
    public OfflineEvaluationResponse evaluate(@RequestBody(required = false) OfflineEvaluationRequest request) {
        return offlineEvaluationService.evaluate(request);
    }

    @GetMapping("/{runId}")
    public OfflineEvaluationResponse get(@PathVariable UUID runId) {
        return offlineEvaluationService.get(runId);
    }
}
