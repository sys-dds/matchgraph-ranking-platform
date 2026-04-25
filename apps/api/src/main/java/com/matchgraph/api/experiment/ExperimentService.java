package com.matchgraph.api.experiment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.shared.cache.OnlineServingCacheService;
import com.matchgraph.api.streaming.RealtimeExperimentGuardrailService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExperimentService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final String BASELINE_RANKING_VERSION = "v1_balanced";

    private final ExperimentRepository experimentRepository;
    private final ProfileService profileService;
    private final OnlineServingCacheService cacheService;
    private final ObjectProvider<RealtimeExperimentGuardrailService> guardrailService;

    @Autowired
    public ExperimentService(ExperimentRepository experimentRepository, ProfileService profileService, OnlineServingCacheService cacheService, ObjectProvider<RealtimeExperimentGuardrailService> guardrailService) {
        this.experimentRepository = experimentRepository;
        this.profileService = profileService;
        this.cacheService = cacheService;
        this.guardrailService = guardrailService;
    }

    public ExperimentService(ExperimentRepository experimentRepository, ProfileService profileService, OnlineServingCacheService cacheService) {
        this(experimentRepository, profileService, cacheService, null);
    }

    @Transactional
    public RankingExperiment create(RankingExperimentCreateRequest request) {
        validate(request);
        return experimentRepository.create(UUID.randomUUID(), normalize(request));
    }

    public RankingExperiment get(String experimentKey) {
        return experimentRepository.find(experimentKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ranking experiment not found"));
    }

    @Transactional
    public RankingExperimentAssignment assign(UUID profileId, String experimentKey) {
        profileService.requireExists(profileId);
        RankingExperiment experiment = get(experimentKey);
        String cacheKey = cacheService.assignmentKey(profileId, experiment.experimentKey());
        Optional<RankingExperimentAssignment> cached = cacheService.get(cacheKey, RankingExperimentAssignment.class);
        Optional<RankingExperimentAssignment> persisted = experimentRepository.assignment(experiment.id(), profileId);
        if (persisted.isPresent()) {
            RankingExperimentAssignment assignment = persisted.get();
            if (cached.isEmpty() || !sameAssignment(cached.get(), assignment)) {
                cacheService.putAssignment(cacheKey, assignment);
            }
            return assignment;
        }
        RankingExperimentAssignment assignment = createAssignment(profileId, experiment);
        cacheService.putAssignment(cacheKey, assignment);
        return assignment;
    }

    private RankingExperimentAssignment createAssignment(UUID profileId, RankingExperiment experiment) {
        String hash = assignmentHash(profileId, experiment.experimentKey());
        BigDecimal bucket = bucket(hash);
        if (!"ACTIVE".equals(experiment.status())) {
            return experimentRepository.createAssignment(
                UUID.randomUUID(),
                experiment,
                profileId,
                null,
                BASELINE_RANKING_VERSION,
                true,
                "EXPERIMENT_NOT_ACTIVE",
                hash
            );
        }
        if (fallbackToControl(experiment.experimentKey())) {
            return experimentRepository.createAssignment(
                UUID.randomUUID(),
                experiment,
                profileId,
                null,
                BASELINE_RANKING_VERSION,
                true,
                "GUARDRAIL_FALLBACK_TO_CONTROL",
                hash
            );
        }
        if (bucket.compareTo(experiment.trafficPercentage()) >= 0) {
            return experimentRepository.createAssignment(
                UUID.randomUUID(),
                experiment,
                profileId,
                null,
                BASELINE_RANKING_VERSION,
                true,
                "OUTSIDE_TRAFFIC",
                hash
            );
        }
        if (bucket.compareTo(experiment.holdoutPercentage()) < 0) {
            return experimentRepository.createAssignment(
                UUID.randomUUID(),
                experiment,
                profileId,
                null,
                BASELINE_RANKING_VERSION,
                true,
                "HOLDOUT",
                hash
            );
        }
        RankingExperimentVariant variant = variantFor(experiment.variants(), bucket.subtract(experiment.holdoutPercentage()));
        return experimentRepository.createAssignment(
            UUID.randomUUID(),
            experiment,
            profileId,
            variant.variantKey(),
            variant.rankingVersion(),
            false,
            "VARIANT_ALLOCATED",
            hash
        );
    }

    private RankingExperimentVariant variantFor(List<RankingExperimentVariant> variants, BigDecimal adjustedBucket) {
        BigDecimal cumulative = BigDecimal.ZERO;
        for (RankingExperimentVariant variant : variants) {
            cumulative = cumulative.add(variant.allocationPercentage());
            if (adjustedBucket.compareTo(cumulative) < 0) {
                return variant;
            }
        }
        return variants.getLast();
    }

    private RankingExperimentCreateRequest normalize(RankingExperimentCreateRequest request) {
        return new RankingExperimentCreateRequest(
            request.experimentKey().trim(),
            request.name().trim(),
            request.status() == null || request.status().isBlank() ? "DRAFT" : request.status().trim(),
            request.trafficPercentage(),
            request.holdoutPercentage(),
            request.guardrailConfig(),
            request.variants()
        );
    }

    private void validate(RankingExperimentCreateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "experiment request is required");
        }
        requireText(request.experimentKey(), "experimentKey is required");
        requireText(request.name(), "name is required");
        if (request.trafficPercentage() == null || request.holdoutPercentage() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trafficPercentage and holdoutPercentage are required");
        }
        validatePercentage(request.trafficPercentage(), "trafficPercentage");
        validatePercentage(request.holdoutPercentage(), "holdoutPercentage");
        if (request.holdoutPercentage().compareTo(request.trafficPercentage()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "holdoutPercentage must not exceed trafficPercentage");
        }
        if (request.variants() == null || request.variants().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one variant is required");
        }
        BigDecimal allocationTotal = BigDecimal.ZERO;
        for (RankingExperimentVariantRequest variant : request.variants()) {
            requireText(variant.variantKey(), "variantKey is required");
            requireText(variant.rankingVersion(), "rankingVersion is required");
            if (variant.allocationPercentage() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "allocationPercentage is required");
            }
            validatePercentage(variant.allocationPercentage(), "allocationPercentage");
            allocationTotal = allocationTotal.add(variant.allocationPercentage());
        }
        BigDecimal nonHoldoutTraffic = request.trafficPercentage().subtract(request.holdoutPercentage());
        if (allocationTotal.compareTo(nonHoldoutTraffic) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "variant allocations must equal non-holdout traffic");
        }
    }

    private boolean sameAssignment(RankingExperimentAssignment cached, RankingExperimentAssignment persisted) {
        return cached.id().equals(persisted.id())
            && cached.experimentId().equals(persisted.experimentId())
            && cached.profileId().equals(persisted.profileId())
            && java.util.Objects.equals(cached.assignedVariantKey(), persisted.assignedVariantKey())
            && cached.assignedRankingVersion().equals(persisted.assignedRankingVersion())
            && cached.holdout() == persisted.holdout()
            && cached.assignmentReason().equals(persisted.assignmentReason())
            && cached.assignmentHash().equals(persisted.assignmentHash());
    }

    private String assignmentHash(UUID profileId, String experimentKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((profileId + ":" + experimentKey).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private boolean fallbackToControl(String experimentKey) {
        if (guardrailService == null) {
            return false;
        }
        RealtimeExperimentGuardrailService service = guardrailService.getIfAvailable();
        return service != null && service.fallbackToControl(experimentKey);
    }

    private BigDecimal bucket(String hash) {
        int raw = Integer.parseUnsignedInt(hash.substring(0, 8), 16);
        return BigDecimal.valueOf(raw % 10_000)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
    }

    private void validatePercentage(BigDecimal value, String field) {
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(ONE_HUNDRED) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be between 0 and 100");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
