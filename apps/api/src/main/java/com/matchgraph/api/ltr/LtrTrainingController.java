package com.matchgraph.api.ltr;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ltr/training/runs")
public class LtrTrainingController {

    private final LtrTrainingService service;

    public LtrTrainingController(LtrTrainingService service) {
        this.service = service;
    }

    @PostMapping
    public LtrTrainingResponse create(@RequestBody LtrTrainingRequest request) {
        return service.train(request);
    }

    @GetMapping("/{trainingRunId}")
    public LtrTrainingRun get(@PathVariable UUID trainingRunId) {
        return service.get(trainingRunId);
    }
}
