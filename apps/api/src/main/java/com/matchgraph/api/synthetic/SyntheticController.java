package com.matchgraph.api.synthetic;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SyntheticController {

    private final SyntheticPopulationService populationService;
    private final GroundTruthEvaluationService evaluationService;

    public SyntheticController(SyntheticPopulationService populationService, GroundTruthEvaluationService evaluationService) {
        this.populationService = populationService;
        this.evaluationService = evaluationService;
    }

    @PostMapping("/api/v1/synthetic/populations")
    public SyntheticPopulationRun createPopulation(@RequestBody SyntheticPopulationRequest request) {
        return populationService.create(request);
    }

    @GetMapping("/api/v1/synthetic/populations/{runId}")
    public SyntheticPopulationRun getPopulation(@PathVariable UUID runId) {
        return populationService.get(runId);
    }

    @PostMapping("/api/v1/synthetic/evaluation/ranking")
    public SyntheticEvaluationRun evaluate(@RequestBody SyntheticEvaluationRequest request) {
        return evaluationService.evaluate(request);
    }

    @GetMapping("/api/v1/synthetic/evaluation/ranking/{evaluationRunId}")
    public SyntheticEvaluationRun getEvaluation(@PathVariable UUID evaluationRunId) {
        return evaluationService.get(evaluationRunId);
    }
}
