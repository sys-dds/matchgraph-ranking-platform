package com.matchgraph.api.ltr;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ltr/models")
public class LtrModelController {

    private final LtrModelRegistryService service;

    public LtrModelController(LtrModelRegistryService service) {
        this.service = service;
    }

    @PostMapping
    public LtrModel createModel(@RequestBody CreateLtrModelRequest request) {
        return service.createModel(request);
    }

    @GetMapping("/{modelKey}")
    public LtrModel getModel(@PathVariable String modelKey) {
        return service.getModel(modelKey);
    }

    @PostMapping("/{modelKey}/versions")
    public LtrModelVersion createVersion(@PathVariable String modelKey, @RequestBody CreateLtrModelVersionRequest request) {
        return service.createVersion(modelKey, request);
    }

    @GetMapping("/{modelKey}/versions/{versionKey}")
    public LtrModelVersion getVersion(@PathVariable String modelKey, @PathVariable String versionKey) {
        return service.getVersion(modelKey, versionKey);
    }

    @PostMapping("/{modelKey}/versions/{versionKey}/transition")
    public LtrModelVersion transition(
        @PathVariable String modelKey,
        @PathVariable String versionKey,
        @RequestBody TransitionLtrModelVersionRequest request
    ) {
        return service.transition(modelKey, versionKey, request);
    }

    @GetMapping("/{modelKey}/versions/{versionKey}/artifact")
    public LtrModelArtifact artifact(@PathVariable String modelKey, @PathVariable String versionKey) {
        return service.getArtifact(modelKey, versionKey);
    }
}
