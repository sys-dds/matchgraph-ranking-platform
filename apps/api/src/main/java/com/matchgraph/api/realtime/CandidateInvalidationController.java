package com.matchgraph.api.realtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.CandidateInvalidation;
import com.matchgraph.api.realtime.RealtimeModels.CandidateInvalidationRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CandidateInvalidationController {

    private final CandidateInvalidationService service;

    public CandidateInvalidationController(CandidateInvalidationService service) {
        this.service = service;
    }

    @PostMapping("/realtime/candidate-invalidations")
    public Map<String, Object> create(@RequestBody CandidateInvalidationRequest request) {
        return Map.of("invalidationId", service.create(request));
    }

    @GetMapping("/profiles/{profileId}/candidate-invalidations")
    public List<CandidateInvalidation> list(@PathVariable UUID profileId) {
        return service.list(profileId);
    }

    @GetMapping("/profiles/{profileId}/candidates/{candidateProfileId}/invalidation-state")
    public Map<String, Object> state(@PathVariable UUID profileId, @PathVariable UUID candidateProfileId) {
        return service.state(profileId, candidateProfileId);
    }
}
