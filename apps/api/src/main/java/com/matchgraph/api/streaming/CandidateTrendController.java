package com.matchgraph.api.streaming;

import java.util.UUID;

import com.matchgraph.api.streaming.StreamingModels.CandidateTrendRun;
import com.matchgraph.api.streaming.StreamingModels.CandidateTrendScore;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CandidateTrendController {

    private final CandidateTrendService service;

    public CandidateTrendController(CandidateTrendService service) {
        this.service = service;
    }

    @PostMapping("/trends/candidates/detect")
    public CandidateTrendRun detect() {
        return service.detect();
    }

    @GetMapping("/trends/candidates/runs/{runId}")
    public CandidateTrendRun run(@PathVariable UUID runId) {
        return service.run(runId);
    }

    @GetMapping("/candidates/{candidateProfileId}/trend")
    public CandidateTrendScore latest(@PathVariable UUID candidateProfileId) {
        return service.latest(candidateProfileId);
    }
}
