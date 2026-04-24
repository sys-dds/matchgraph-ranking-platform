package com.matchgraph.api.retrieval;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles/{profileId}/retrieval")
public class CandidateRetrievalController {

    private final CandidateRetrievalService candidateRetrievalService;

    public CandidateRetrievalController(CandidateRetrievalService candidateRetrievalService) {
        this.candidateRetrievalService = candidateRetrievalService;
    }

    @PostMapping("/run")
    public CandidateRetrievalRun run(@PathVariable UUID profileId, @RequestBody(required = false) RunRetrievalRequest request) {
        return candidateRetrievalService.run(profileId, request);
    }

    @GetMapping("/runs/{runId}")
    public CandidateRetrievalRun get(@PathVariable UUID profileId, @PathVariable UUID runId) {
        return candidateRetrievalService.get(profileId, runId);
    }
}
