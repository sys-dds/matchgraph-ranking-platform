package com.matchgraph.api.realtime;

import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.NearlineFeatureMaterializationRequest;
import com.matchgraph.api.realtime.RealtimeModels.NearlineFeatureMaterializationRun;
import com.matchgraph.api.realtime.RealtimeModels.NearlineFeatureSnapshot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class NearlineFeatureController {

    private final NearlineFeatureMaterializerService service;

    public NearlineFeatureController(NearlineFeatureMaterializerService service) {
        this.service = service;
    }

    @PostMapping("/nearline/features/materialize")
    public NearlineFeatureMaterializationRun materialize(@RequestBody NearlineFeatureMaterializationRequest request) {
        return service.materialize(request);
    }

    @GetMapping("/profiles/{profileId}/nearline/features")
    public NearlineFeatureSnapshot profile(@PathVariable UUID profileId) {
        return service.profile(profileId);
    }

    @GetMapping("/profiles/{profileId}/candidates/{candidateProfileId}/nearline/features")
    public NearlineFeatureSnapshot pair(@PathVariable UUID profileId, @PathVariable UUID candidateProfileId) {
        return service.pair(profileId, candidateProfileId);
    }
}
