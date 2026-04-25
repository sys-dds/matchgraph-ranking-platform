package com.matchgraph.api.causal;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/causal")
public class CausalEvaluationController {

    private final PropensityLoggingService propensityLoggingService;
    private final CausalEvaluationService causalEvaluationService;

    public CausalEvaluationController(PropensityLoggingService propensityLoggingService, CausalEvaluationService causalEvaluationService) {
        this.propensityLoggingService = propensityLoggingService;
        this.causalEvaluationService = causalEvaluationService;
    }

    @PostMapping("/propensity/backfill")
    public PropensityBackfillResult backfill(@RequestBody PropensityBackfillRequest request) {
        return propensityLoggingService.backfill(request);
    }

    @PostMapping("/evaluation/ranking")
    public CausalEvaluationRun evaluate(@RequestBody CausalEvaluationRequest request) {
        return causalEvaluationService.evaluate(request);
    }

    @GetMapping("/evaluation/ranking/{runId}")
    public CausalEvaluationRun get(@PathVariable UUID runId) {
        return causalEvaluationService.get(runId);
    }
}
