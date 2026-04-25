package com.matchgraph.api.streaming;

import java.util.List;

import com.matchgraph.api.streaming.StreamingModels.ExperimentGuardrailDecision;
import com.matchgraph.api.streaming.StreamingModels.ExperimentGuardrailRun;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/experiments")
public class ExperimentGuardrailController {

    private final RealtimeExperimentGuardrailService service;

    public ExperimentGuardrailController(RealtimeExperimentGuardrailService service) {
        this.service = service;
    }

    @PostMapping("/guardrails/evaluate")
    public ExperimentGuardrailRun evaluateDefault() {
        return service.evaluate("default-recommendation-experiment");
    }

    @GetMapping("/{experimentKey}/guardrails")
    public List<ExperimentGuardrailDecision> decisions(@PathVariable String experimentKey) {
        return service.decisions(experimentKey);
    }

    @PostMapping("/{experimentKey}/guardrails/pause-if-bad")
    public ExperimentGuardrailRun pauseIfBad(@PathVariable String experimentKey) {
        return service.pauseIfBad(experimentKey);
    }
}
