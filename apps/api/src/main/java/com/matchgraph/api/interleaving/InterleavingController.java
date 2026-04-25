package com.matchgraph.api.interleaving;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InterleavingController {

    private final InterleavingService service;

    public InterleavingController(InterleavingService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/interleaving/experiments")
    public InterleavingExperiment createExperiment(@RequestBody InterleavingExperimentRequest request) {
        return service.createExperiment(request);
    }

    @GetMapping("/api/v1/interleaving/experiments/{experimentKey}")
    public InterleavingExperiment getExperiment(@PathVariable String experimentKey) {
        return service.getExperiment(experimentKey);
    }

    @PostMapping("/api/v1/profiles/{profileId}/interleaving/{experimentKey}/sessions")
    public InterleavingSession createSession(
        @PathVariable UUID profileId,
        @PathVariable String experimentKey,
        @RequestBody InterleavingSessionRequest request
    ) {
        return service.createSession(profileId, experimentKey, request);
    }

    @GetMapping("/api/v1/interleaving/sessions/{sessionId}")
    public InterleavingSession getSession(@PathVariable UUID sessionId) {
        return service.getSession(sessionId);
    }

    @PostMapping("/api/v1/interleaving/sessions/{sessionId}/outcomes")
    public InterleavingOutcome outcome(@PathVariable UUID sessionId, @RequestBody InterleavingOutcomeRequest request) {
        return service.outcome(sessionId, request);
    }

    @GetMapping("/api/v1/interleaving/experiments/{experimentKey}/summary")
    public Map<String, Object> summary(@PathVariable String experimentKey) {
        return service.summary(experimentKey);
    }
}
