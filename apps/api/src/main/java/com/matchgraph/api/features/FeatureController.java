package com.matchgraph.api.features;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class FeatureController {

    private final FeatureService featureService;

    public FeatureController(FeatureService featureService) {
        this.featureService = featureService;
    }

    @PutMapping("/profiles/{profileId}/features")
    public FeatureResponse upsertProfileFeature(@PathVariable UUID profileId, @RequestBody UpsertFeatureRequest request) {
        return featureService.upsertProfileFeature(profileId, request);
    }

    @GetMapping("/profiles/{profileId}/features")
    public List<FeatureResponse> findProfileFeatures(@PathVariable UUID profileId) {
        return featureService.findProfileFeatures(profileId);
    }

    @PutMapping("/items/{itemId}/features")
    public FeatureResponse upsertItemFeature(@PathVariable UUID itemId, @RequestBody UpsertFeatureRequest request) {
        return featureService.upsertItemFeature(itemId, request);
    }

    @GetMapping("/items/{itemId}/features")
    public List<FeatureResponse> findItemFeatures(@PathVariable UUID itemId) {
        return featureService.findItemFeatures(itemId);
    }
}
