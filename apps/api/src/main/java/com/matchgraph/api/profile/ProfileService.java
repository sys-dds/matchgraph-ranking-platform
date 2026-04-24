package com.matchgraph.api.profile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {

    private static final int EMBEDDING_DIMENSIONS = 384;
    private static final Set<String> PROFILE_TYPES = Set.of("USER", "CREATOR", "BUSINESS");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final ProfileRepository profileRepository;

    public ProfileService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @Transactional
    public ProfileResponse create(CreateProfileRequest request) {
        requireText(request.externalRef(), "externalRef is required");
        requireText(request.displayName(), "displayName is required");
        String profileType = requireOneOf(request.profileType(), PROFILE_TYPES, "Invalid profileType");
        String status = request.status() == null || request.status().isBlank() ? "ACTIVE" : requireOneOf(request.status(), STATUSES, "Invalid status");

        UUID id = UUID.randomUUID();
        CreateProfileRequest normalized = new CreateProfileRequest(
            request.externalRef(),
            request.displayName(),
            profileType,
            status,
            request.bio(),
            request.city(),
            request.region(),
            request.country()
        );
        ProfileResponse created = profileRepository.create(id, normalized, calculateCompleteness(normalized));
        profileRepository.createDefaultSafetyState(id);
        profileRepository.createSafetyEvent(UUID.randomUUID(), id, "UNREVIEWED", "Profile created");
        return hydrate(created);
    }

    public ProfileResponse get(UUID id) {
        return hydrate(requireProfile(id));
    }

    public List<ProfileResponse> find(String profileType, String status, Integer limit) {
        String validType = profileType == null || profileType.isBlank() ? null : requireOneOf(profileType, PROFILE_TYPES, "Invalid profileType");
        String validStatus = status == null || status.isBlank() ? null : requireOneOf(status, STATUSES, "Invalid status");
        return profileRepository.find(validType, validStatus, sanitizeLimit(limit)).stream()
            .map(this::hydrate)
            .toList();
    }

    @Transactional
    public ProfileResponse update(UUID id, UpdateProfileRequest request) {
        ProfileResponse existing = requireProfile(id);
        String status = request.status() == null || request.status().isBlank() ? null : requireOneOf(request.status(), STATUSES, "Invalid status");
        String displayName = request.displayName() == null || request.displayName().isBlank() ? existing.displayName() : request.displayName();
        UpdateProfileRequest normalized = new UpdateProfileRequest(
            displayName,
            status,
            request.bio() == null ? existing.bio() : request.bio(),
            request.city() == null ? existing.city() : request.city(),
            request.region() == null ? existing.region() : request.region(),
            request.country() == null ? existing.country() : request.country(),
            request.lastActiveAt()
        );
        BigDecimal completeness = calculateCompleteness(normalized, profileRepository.findInterests(id), profileRepository.findLocation(id).orElse(null), existing.embeddingStatus());
        ProfileResponse updated = profileRepository.update(id, normalized, completeness);
        profileRepository.markEmbeddingStaleIfCurrent(id);
        return hydrate(updated);
    }

    @Transactional
    public List<ProfileInterestResponse> updateInterests(UUID id, UpdateProfileInterestsRequest request) {
        requireExists(id);
        List<ProfileInterestRequest> interests = normalizeInterests(request);
        profileRepository.replaceInterests(id, interests);
        profileRepository.markEmbeddingStaleIfCurrent(id);
        recalculateCompleteness(id);
        return profileRepository.findInterests(id);
    }

    @Transactional
    public ProfileLocationResponse updateLocation(UUID id, UpdateProfileLocationRequest request) {
        requireExists(id);
        validateLocation(request);
        BigDecimal precisionKm = request.precisionKm() == null ? BigDecimal.valueOf(25) : request.precisionKm();
        ProfileLocationResponse location = profileRepository.upsertLocation(
            id,
            new UpdateProfileLocationRequest(
                request.latitude(),
                request.longitude(),
                precisionKm,
                request.city(),
                request.region(),
                request.country()
            )
        );
        recalculateCompleteness(id);
        return location;
    }

    @Transactional
    public ProfileEmbeddingStatusResponse upsertEmbedding(UUID id, UpsertProfileEmbeddingRequest request) {
        requireExists(id);
        validateEmbedding(request);
        String versionName = request.versionName().trim();
        String modelName = request.modelName().trim();
        profileRepository.upsertEmbeddingVersion(UUID.randomUUID(), versionName, modelName);
        UUID versionId = profileRepository.findEmbeddingVersionId(versionName);
        profileRepository.upsertEmbedding(id, versionId, vectorLiteral(request.embedding()));
        profileRepository.updateEmbeddingStatus(id, "CURRENT");
        recalculateCompleteness(id);
        return embeddingStatus(id);
    }

    public ProfileEmbeddingStatusResponse embeddingStatus(UUID id) {
        requireExists(id);
        return profileRepository.embeddingStatus(id);
    }

    public ProfileSafetyStateResponse safetyState(UUID id) {
        requireExists(id);
        return profileRepository.safetyState(id);
    }

    public void requireExists(UUID id) {
        if (!profileRepository.exists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found");
        }
    }

    private ProfileResponse requireProfile(UUID id) {
        return profileRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found"));
    }

    private ProfileResponse hydrate(ProfileResponse profile) {
        return new ProfileResponse(
            profile.id(),
            profile.externalRef(),
            profile.displayName(),
            profile.profileType(),
            profile.status(),
            profile.bio(),
            profile.city(),
            profile.region(),
            profile.country(),
            profile.lastActiveAt(),
            profile.profileCompletenessScore(),
            profile.embeddingStatus(),
            profileRepository.findInterests(profile.id()),
            profileRepository.findLocation(profile.id()).orElse(null),
            profile.createdAt(),
            profile.updatedAt()
        );
    }

    private void recalculateCompleteness(UUID id) {
        ProfileResponse profile = requireProfile(id);
        BigDecimal completeness = calculateCompleteness(
            profile,
            profileRepository.findInterests(id),
            profileRepository.findLocation(id).orElse(null)
        );
        profileRepository.updateCompletenessScore(id, completeness);
    }

    private BigDecimal calculateCompleteness(CreateProfileRequest request) {
        int complete = 0;
        complete += hasText(request.displayName()) ? 1 : 0;
        complete += hasText(request.bio()) ? 1 : 0;
        complete += hasText(request.city()) ? 1 : 0;
        complete += hasText(request.region()) ? 1 : 0;
        complete += hasText(request.country()) ? 1 : 0;
        return completeness(complete, 8);
    }

    private BigDecimal calculateCompleteness(UpdateProfileRequest request, List<ProfileInterestResponse> interests, ProfileLocationResponse location, String embeddingStatus) {
        int complete = 0;
        complete += hasText(request.displayName()) ? 1 : 0;
        complete += hasText(request.bio()) ? 1 : 0;
        complete += hasText(request.city()) ? 1 : 0;
        complete += hasText(request.region()) ? 1 : 0;
        complete += hasText(request.country()) ? 1 : 0;
        complete += interests.isEmpty() ? 0 : 1;
        complete += location == null ? 0 : 1;
        complete += "CURRENT".equals(embeddingStatus) ? 1 : 0;
        return completeness(complete, 8);
    }

    private BigDecimal calculateCompleteness(ProfileResponse profile, List<ProfileInterestResponse> interests, ProfileLocationResponse location) {
        int complete = 0;
        complete += hasText(profile.displayName()) ? 1 : 0;
        complete += hasText(profile.bio()) ? 1 : 0;
        complete += hasText(profile.city()) ? 1 : 0;
        complete += hasText(profile.region()) ? 1 : 0;
        complete += hasText(profile.country()) ? 1 : 0;
        complete += interests.isEmpty() ? 0 : 1;
        complete += location == null ? 0 : 1;
        complete += "CURRENT".equals(profile.embeddingStatus()) ? 1 : 0;
        return completeness(complete, 8);
    }

    private BigDecimal completeness(int complete, int total) {
        return BigDecimal.valueOf(complete)
            .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private List<ProfileInterestRequest> normalizeInterests(UpdateProfileInterestsRequest request) {
        List<ProfileInterestRequest> interests = request.interests() == null ? List.of() : request.interests();
        List<String> keys = new ArrayList<>();
        List<ProfileInterestRequest> normalized = new ArrayList<>();
        for (ProfileInterestRequest interest : interests) {
            requireText(interest.interestKey(), "interestKey is required");
            requireText(interest.interestValue(), "interestValue is required");
            BigDecimal weight = interest.weight() == null ? BigDecimal.ONE : interest.weight();
            if (weight.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "interest weight must be non-negative");
            }
            String identity = interest.interestKey().trim() + "=" + interest.interestValue().trim();
            keys.add(identity);
            normalized.add(new ProfileInterestRequest(interest.interestKey(), interest.interestValue(), weight));
        }
        if (keys.size() != keys.stream().collect(Collectors.toSet()).size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duplicate interests are not allowed");
        }
        return normalized;
    }

    private void validateLocation(UpdateProfileLocationRequest request) {
        if (request.latitude() == null || request.longitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "latitude and longitude are required");
        }
        if (request.latitude().compareTo(BigDecimal.valueOf(-90)) < 0 || request.latitude().compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "latitude must be between -90 and 90");
        }
        if (request.longitude().compareTo(BigDecimal.valueOf(-180)) < 0 || request.longitude().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "longitude must be between -180 and 180");
        }
        if (request.precisionKm() != null && request.precisionKm().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "precisionKm must be positive");
        }
    }

    private void validateEmbedding(UpsertProfileEmbeddingRequest request) {
        requireText(request.versionName(), "versionName is required");
        requireText(request.modelName(), "modelName is required");
        if (request.embedding() == null || request.embedding().size() != EMBEDDING_DIMENSIONS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "embedding must have 384 dimensions");
        }
    }

    private String vectorLiteral(List<Double> embedding) {
        return embedding.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(",", "[", "]"));
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
        if (!hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
