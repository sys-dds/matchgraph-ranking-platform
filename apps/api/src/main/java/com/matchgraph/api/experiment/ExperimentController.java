package com.matchgraph.api.experiment;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ExperimentController {

    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @PostMapping("/experiments/ranking")
    public RankingExperiment create(@RequestBody RankingExperimentCreateRequest request) {
        return experimentService.create(request);
    }

    @GetMapping("/experiments/ranking/{experimentKey}")
    public RankingExperiment get(@PathVariable String experimentKey) {
        return experimentService.get(experimentKey);
    }

    @PostMapping("/profiles/{profileId}/experiments/ranking/{experimentKey}/assignment")
    public RankingExperimentAssignment assign(@PathVariable UUID profileId, @PathVariable String experimentKey) {
        return experimentService.assign(profileId, experimentKey);
    }
}
