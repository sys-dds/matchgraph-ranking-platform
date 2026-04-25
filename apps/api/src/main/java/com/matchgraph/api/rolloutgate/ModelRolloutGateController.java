package com.matchgraph.api.rolloutgate;

import java.util.UUID;

import com.matchgraph.api.ltr.LtrModelVersion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ltr")
public class ModelRolloutGateController {

    private final ModelRolloutGateService service;

    public ModelRolloutGateController(ModelRolloutGateService service) {
        this.service = service;
    }

    @PostMapping("/rollout-gates")
    public ModelRolloutGateRun create(@RequestBody ModelRolloutGateRequest request) {
        return service.create(request);
    }

    @GetMapping("/rollout-gates/{gateRunId}")
    public ModelRolloutGateRun get(@PathVariable UUID gateRunId) {
        return service.get(gateRunId);
    }

    @GetMapping("/models/{modelKey}/versions/{versionKey}/acceptance-report")
    public ModelAcceptanceReport report(@PathVariable String modelKey, @PathVariable String versionKey) {
        return service.report(modelKey, versionKey);
    }

    @PostMapping("/models/{modelKey}/versions/{versionKey}/approve-if-safe")
    public LtrModelVersion approveIfSafe(@PathVariable String modelKey, @PathVariable String versionKey) {
        return service.approveIfSafe(modelKey, versionKey);
    }
}
