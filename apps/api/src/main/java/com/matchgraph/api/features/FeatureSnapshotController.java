package com.matchgraph.api.features;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles/{profileId}/feature-snapshots")
public class FeatureSnapshotController {

    private final FeatureSnapshotService featureSnapshotService;

    public FeatureSnapshotController(FeatureSnapshotService featureSnapshotService) {
        this.featureSnapshotService = featureSnapshotService;
    }

    @PostMapping("/from-retrieval/{retrievalRunId}")
    public FeatureSnapshotRun createFromRetrieval(@PathVariable UUID profileId, @PathVariable UUID retrievalRunId) {
        return featureSnapshotService.createFromRetrieval(profileId, retrievalRunId);
    }

    @GetMapping("/{snapshotRunId}")
    public FeatureSnapshotRun get(@PathVariable UUID profileId, @PathVariable UUID snapshotRunId) {
        return featureSnapshotService.get(profileId, snapshotRunId);
    }
}
