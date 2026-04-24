package com.matchgraph.api.profile;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileResponse create(@RequestBody CreateProfileRequest request) {
        return profileService.create(request);
    }

    @GetMapping("/{id}")
    public ProfileResponse get(@PathVariable UUID id) {
        return profileService.get(id);
    }

    @PatchMapping("/{id}")
    public ProfileResponse update(@PathVariable UUID id, @RequestBody UpdateProfileRequest request) {
        return profileService.update(id, request);
    }

    @GetMapping
    public List<ProfileResponse> find(
        @RequestParam(required = false) String profileType,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer limit
    ) {
        return profileService.find(profileType, status, limit);
    }

    @PutMapping("/{id}/interests")
    public List<ProfileInterestResponse> updateInterests(@PathVariable UUID id, @RequestBody UpdateProfileInterestsRequest request) {
        return profileService.updateInterests(id, request);
    }

    @PutMapping("/{id}/location")
    public ProfileLocationResponse updateLocation(@PathVariable UUID id, @RequestBody UpdateProfileLocationRequest request) {
        return profileService.updateLocation(id, request);
    }

    @PutMapping("/{id}/embedding")
    public ProfileEmbeddingStatusResponse upsertEmbedding(@PathVariable UUID id, @RequestBody UpsertProfileEmbeddingRequest request) {
        return profileService.upsertEmbedding(id, request);
    }

    @GetMapping("/{id}/embedding/status")
    public ProfileEmbeddingStatusResponse embeddingStatus(@PathVariable UUID id) {
        return profileService.embeddingStatus(id);
    }

    @GetMapping("/{id}/safety")
    public ProfileSafetyStateResponse safety(@PathVariable UUID id) {
        return profileService.safetyState(id);
    }
}
