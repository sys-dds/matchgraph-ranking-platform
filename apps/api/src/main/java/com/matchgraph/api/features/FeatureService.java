package com.matchgraph.api.features;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.matchgraph.api.feed.RankableItemService;
import com.matchgraph.api.profile.ProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FeatureService {

    private final FeatureRepository featureRepository;
    private final ProfileService profileService;
    private final RankableItemService itemService;

    public FeatureService(FeatureRepository featureRepository, ProfileService profileService, RankableItemService itemService) {
        this.featureRepository = featureRepository;
        this.profileService = profileService;
        this.itemService = itemService;
    }

    public FeatureResponse upsertProfileFeature(UUID profileId, UpsertFeatureRequest request) {
        profileService.requireExists(profileId);
        return featureRepository.upsertProfileFeature(profileId, normalize(request));
    }

    public List<FeatureResponse> findProfileFeatures(UUID profileId) {
        profileService.requireExists(profileId);
        return featureRepository.findProfileFeatures(profileId);
    }

    public FeatureResponse upsertItemFeature(UUID itemId, UpsertFeatureRequest request) {
        itemService.requireExists(itemId);
        return featureRepository.upsertItemFeature(itemId, normalize(request));
    }

    public List<FeatureResponse> findItemFeatures(UUID itemId) {
        itemService.requireExists(itemId);
        return featureRepository.findItemFeatures(itemId);
    }

    private UpsertFeatureRequest normalize(UpsertFeatureRequest request) {
        if (request.featureKey() == null || request.featureKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "featureKey is required");
        }
        if (request.featureValue() == null || request.featureValue().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "featureValue is required");
        }
        BigDecimal weight = request.weight() == null ? BigDecimal.ONE : request.weight();
        if (weight.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "weight must be non-negative");
        }
        return new UpsertFeatureRequest(request.featureKey(), request.featureValue(), weight);
    }
}
