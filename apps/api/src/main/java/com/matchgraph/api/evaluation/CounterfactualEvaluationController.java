package com.matchgraph.api.evaluation;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluation/counterfactual/ranking")
public class CounterfactualEvaluationController {

    private final CounterfactualEvaluationService counterfactualEvaluationService;

    public CounterfactualEvaluationController(CounterfactualEvaluationService counterfactualEvaluationService) {
        this.counterfactualEvaluationService = counterfactualEvaluationService;
    }

    @PostMapping
    public CounterfactualEvaluationResponse evaluate(@RequestBody CounterfactualEvaluationRequest request) {
        return counterfactualEvaluationService.evaluate(request);
    }

    @GetMapping("/{runId}")
    public CounterfactualEvaluationResponse get(@PathVariable UUID runId) {
        return counterfactualEvaluationService.get(runId);
    }
}
