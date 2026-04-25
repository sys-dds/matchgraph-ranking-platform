package com.matchgraph.api.featureparity;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/features/parity")
public class FeatureParityController {

    private final FeatureParityService service;

    public FeatureParityController(FeatureParityService service) {
        this.service = service;
    }

    @PostMapping("/check")
    public FeatureParityRun check(@RequestBody FeatureParityCheckRequest request) {
        return service.check(request);
    }

    @GetMapping("/runs/{runId}")
    public FeatureParityRun get(@PathVariable UUID runId) {
        return service.get(runId);
    }
}
