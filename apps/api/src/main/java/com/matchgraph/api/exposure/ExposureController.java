package com.matchgraph.api.exposure;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExposureController {

    private final ExposurePolicyService policyService;
    private final ExposureRepository repository;
    private final ExposureLedgerService ledgerService;

    public ExposureController(
        ExposurePolicyService policyService,
        ExposureRepository repository,
        ExposureLedgerService ledgerService
    ) {
        this.policyService = policyService;
        this.repository = repository;
        this.ledgerService = ledgerService;
    }

    @PostMapping("/api/v1/exposure/policies")
    public ExposureControlPolicy create(@RequestBody ExposurePolicyRequest request) {
        return policyService.create(request);
    }

    @GetMapping("/api/v1/exposure/policies/{policyKey}")
    public ExposureControlPolicy get(@PathVariable String policyKey) {
        return policyService.get(policyKey);
    }

    @GetMapping("/api/v1/profiles/{candidateProfileId}/exposure/summary")
    public Map<String, Object> candidateSummary(@PathVariable UUID candidateProfileId) {
        List<CandidateExposureEvent> exposures = repository.exposures(candidateProfileId);
        return Map.of(
            "candidateProfileId", candidateProfileId,
            "exposureCount", exposures.size(),
            "latestExposures", exposures.stream().limit(10).toList()
        );
    }

    @PostMapping("/api/v1/exposure/recompute-windows")
    public Map<String, Object> recompute(@RequestBody Map<String, String> request) {
        String policyKey = request == null ? null : request.get("policyKey");
        if (policyKey != null) {
            ledgerService.recomputeWindows(policyKey);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("policyKey", policyKey);
        response.put("status", "COMPLETED");
        response.put("windowMode", "LAZY_LOCAL");
        return response;
    }

    @GetMapping("/api/v1/exposure/policies/{policyKey}/summary")
    public Map<String, Object> policySummary(@PathVariable String policyKey) {
        return policyService.summary(policyKey);
    }
}
