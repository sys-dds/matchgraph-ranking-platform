package com.matchgraph.api.training;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/training/datasets")
public class TrainingDatasetController {

    private final TrainingDatasetService service;

    public TrainingDatasetController(TrainingDatasetService service) {
        this.service = service;
    }

    @PostMapping
    public TrainingDatasetRun create(@RequestBody CreateTrainingDatasetRequest request) {
        return service.create(request);
    }

    @GetMapping("/{datasetRunId}")
    public TrainingDatasetRun get(@PathVariable UUID datasetRunId) {
        return service.get(datasetRunId);
    }

    @GetMapping("/{datasetRunId}/examples")
    public List<TrainingExample> examples(@PathVariable UUID datasetRunId) {
        return service.examples(datasetRunId);
    }

    @GetMapping("/{datasetRunId}/quality")
    public TrainingDatasetQualityReport quality(@PathVariable UUID datasetRunId) {
        return service.quality(datasetRunId);
    }
}
