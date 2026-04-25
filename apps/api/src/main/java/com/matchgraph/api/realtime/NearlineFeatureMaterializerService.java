package com.matchgraph.api.realtime;

import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.NearlineFeatureMaterializationRequest;
import com.matchgraph.api.realtime.RealtimeModels.NearlineFeatureMaterializationRun;
import com.matchgraph.api.realtime.RealtimeModels.NearlineFeatureSnapshot;

import org.springframework.stereotype.Service;

@Service
public class NearlineFeatureMaterializerService {
    private final NearlineFeatureRepository repository;

    public NearlineFeatureMaterializerService(NearlineFeatureRepository repository) {
        this.repository = repository;
    }

    public NearlineFeatureMaterializationRun materialize(NearlineFeatureMaterializationRequest request) {
        UUID runId = repository.materialize(request.profileId(), request.candidateProfileId());
        return new NearlineFeatureMaterializationRun(runId, request.profileId(), request.candidateProfileId(), "COMPLETED", java.util.Map.of("freshnessStatus", "FRESH"));
    }

    public NearlineFeatureSnapshot profile(UUID profileId) {
        return new NearlineFeatureSnapshot(profileId, null, repository.profileFeatures(profileId), java.util.Map.of(), java.util.Map.of());
    }

    public NearlineFeatureSnapshot pair(UUID profileId, UUID candidateId) {
        return new NearlineFeatureSnapshot(profileId, candidateId, repository.profileFeatures(profileId), repository.candidateFeatures(candidateId), repository.pairFeatures(profileId, candidateId));
    }
}
