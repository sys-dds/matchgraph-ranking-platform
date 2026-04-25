package com.matchgraph.api.streaming;

import java.util.List;
import java.util.UUID;

import com.matchgraph.api.streaming.StreamingModels.CandidateFeatureWindow;
import com.matchgraph.api.streaming.StreamingModels.FeatureWindowRun;
import com.matchgraph.api.streaming.StreamingModels.ProfileFeatureWindow;
import com.matchgraph.api.streaming.StreamingModels.StreamingFeatureWindowRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class StreamingFeatureWindowController {

    private final StreamingFeatureWindowService service;

    public StreamingFeatureWindowController(StreamingFeatureWindowService service) {
        this.service = service;
    }

    @PostMapping("/streaming/feature-windows/materialize")
    public FeatureWindowRun materialize(@RequestBody(required = false) StreamingFeatureWindowRequest request) {
        return service.materialize(request);
    }

    @GetMapping("/profiles/{profileId}/streaming/windows")
    public List<ProfileFeatureWindow> profile(@PathVariable UUID profileId) {
        return service.profileWindows(profileId);
    }

    @GetMapping("/candidates/{candidateProfileId}/streaming/windows")
    public List<CandidateFeatureWindow> candidate(@PathVariable UUID candidateProfileId) {
        return service.candidateWindows(candidateProfileId);
    }

    @GetMapping("/sources/{sourceKey}/streaming/windows")
    public List<StreamingModels.SourceFeatureWindow> source(@PathVariable String sourceKey) {
        return service.sourceWindows(sourceKey);
    }

    @GetMapping("/surfaces/{surfaceKey}/streaming/windows")
    public List<StreamingModels.SurfaceFeatureWindow> surface(@PathVariable String surfaceKey) {
        return service.surfaceWindows(surfaceKey);
    }
}
