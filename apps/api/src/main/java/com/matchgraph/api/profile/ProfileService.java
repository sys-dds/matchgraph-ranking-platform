package com.matchgraph.api.profile;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {

    private static final Set<String> PROFILE_TYPES = Set.of("USER", "CREATOR", "BUSINESS");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final ProfileRepository profileRepository;

    public ProfileService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    public ProfileResponse create(CreateProfileRequest request) {
        requireText(request.externalRef(), "externalRef is required");
        requireText(request.displayName(), "displayName is required");
        String profileType = requireOneOf(request.profileType(), PROFILE_TYPES, "Invalid profileType");
        String status = request.status() == null || request.status().isBlank() ? "ACTIVE" : requireOneOf(request.status(), STATUSES, "Invalid status");
        return profileRepository.create(new CreateProfileRequest(request.externalRef(), request.displayName(), profileType, status));
    }

    public ProfileResponse get(UUID id) {
        return profileRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found"));
    }

    public List<ProfileResponse> find(String profileType, String status, Integer limit) {
        String validType = profileType == null || profileType.isBlank() ? null : requireOneOf(profileType, PROFILE_TYPES, "Invalid profileType");
        String validStatus = status == null || status.isBlank() ? null : requireOneOf(status, STATUSES, "Invalid status");
        return profileRepository.find(validType, validStatus, sanitizeLimit(limit));
    }

    public void requireExists(UUID id) {
        if (!profileRepository.exists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found");
        }
    }

    private int sanitizeLimit(Integer limit) {
        if (limit == null) {
            return 50;
        }
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        return limit;
    }

    private String requireOneOf(String value, Set<String> allowed, String message) {
        if (value == null || !allowed.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
