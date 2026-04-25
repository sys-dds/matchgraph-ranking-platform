package com.matchgraph.api.features;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.retrieval.CandidateRetrievalService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FeatureSnapshotService {

    private static final List<String> GRAPH_SOURCE_TYPES = List.of(
        "GRAPH_TWO_HOP",
        "GRAPH_MUTUALS",
        "WEAK_TIE_EXPLORATION"
    );

    private static final List<String> REQUIRED_FEATURES = List.of(
        "shared_interest_count",
        "source_count",
        "has_graph_source",
        "graph_distance",
        "mutual_count",
        "common_neighbour_count",
        "has_vector_source",
        "vector_distance",
        "embedding_version",
        "has_location_source",
        "distance_band",
        "approximate_distance_km",
        "last_active_age_hours",
        "profile_completeness_score",
        "safety_state",
        "candidate_source_set",
        "retrieval_source_reason_json"
    );

    private final FeatureSnapshotRepository featureSnapshotRepository;
    private final CandidateRetrievalService candidateRetrievalService;
    private final ProfileService profileService;

    public FeatureSnapshotService(
        FeatureSnapshotRepository featureSnapshotRepository,
        CandidateRetrievalService candidateRetrievalService,
        ProfileService profileService
    ) {
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.candidateRetrievalService = candidateRetrievalService;
        this.profileService = profileService;
    }

    @Transactional
    public FeatureSnapshotRun createFromRetrieval(UUID profileId, UUID retrievalRunId) {
        profileService.requireExists(profileId);
        candidateRetrievalService.get(profileId, retrievalRunId);

        UUID snapshotRunId = featureSnapshotRepository.createRun(profileId, retrievalRunId);
        int staleCount = 0;
        int missingRequiredCount = 0;
        int candidateCount = 0;

        for (FeatureSnapshotRepository.RetrievalCandidateFact candidate : featureSnapshotRepository.retrievalCandidates(profileId, retrievalRunId)) {
            FeatureMaterialization materialization = materialize(profileId, candidate);
            staleCount += materialization.staleFeatureCount();
            missingRequiredCount += materialization.missingRequiredFeatureCount();
            UUID snapshotId = featureSnapshotRepository.createCandidateSnapshot(
                snapshotRunId,
                candidate.candidateProfileId(),
                retrievalRunId,
                candidate.sourceTypes(),
                materialization.overallFreshness()
            );
            for (CandidateFeatureValue value : materialization.values()) {
                featureSnapshotRepository.insertValue(snapshotId, value);
            }
            candidateCount++;
        }

        featureSnapshotRepository.completeRun(snapshotRunId, candidateCount, staleCount, missingRequiredCount);
        return get(profileId, snapshotRunId);
    }

    public FeatureSnapshotRun get(UUID profileId, UUID snapshotRunId) {
        profileService.requireExists(profileId);
        return featureSnapshotRepository.findRun(profileId, snapshotRunId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "feature snapshot run not found"));
    }

    private FeatureMaterialization materialize(UUID profileId, FeatureSnapshotRepository.RetrievalCandidateFact retrievalFact) {
        FeatureSnapshotRepository.CandidateProfileFact profileFact = featureSnapshotRepository
            .candidateProfile(profileId, retrievalFact.candidateProfileId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "candidate profile not found"));
        FeatureSnapshotRepository.GraphFact graphFact = featureSnapshotRepository.graphFact(profileId, retrievalFact.candidateProfileId());
        BigDecimal vectorDistance = featureSnapshotRepository.vectorDistance(profileId, retrievalFact.candidateProfileId());
        int sharedInterestCount = featureSnapshotRepository.sharedInterestCount(profileId, retrievalFact.candidateProfileId());
        String dominantInterest = featureSnapshotRepository.dominantInterest(retrievalFact.candidateProfileId()).orElse(null);

        List<CandidateFeatureValue> values = new ArrayList<>();
        values.add(numeric("shared_interest_count", BigDecimal.valueOf(sharedInterestCount), "FRESH"));
        values.add(numeric("source_count", BigDecimal.valueOf(retrievalFact.sourceTypes().size()), "FRESH"));
        values.add(numeric("has_graph_source", bool(hasAnySource(retrievalFact.sourceTypes(), GRAPH_SOURCE_TYPES)), "FRESH"));
        values.add(numeric("graph_distance", graphFact.graphDistance(), graphFact.graphDistance() == null ? "MISSING" : "FRESH"));
        values.add(numeric("mutual_count", BigDecimal.valueOf(graphFact.mutualCount()), "FRESH"));
        values.add(numeric("common_neighbour_count", BigDecimal.valueOf(graphFact.commonNeighbourCount()), "FRESH"));
        values.add(numeric("has_vector_source", bool(retrievalFact.sourceTypes().contains("VECTOR_SIMILARITY")), "FRESH"));
        values.add(numeric("vector_distance", vectorDistance, vectorDistance == null ? "MISSING" : embeddingFreshness(profileFact)));
        values.add(text("embedding_version", profileFact.embeddingVersion(), profileFact.embeddingVersion() == null ? "MISSING" : embeddingFreshness(profileFact)));
        values.add(numeric("has_location_source", bool(retrievalFact.sourceTypes().contains("LOCATION_NEARBY")), "FRESH"));
        values.add(text("distance_band", distanceBand(profileFact.approximateDistanceKm()), profileFact.approximateDistanceKm() == null ? "MISSING" : locationFreshness(profileFact)));
        values.add(numeric("approximate_distance_km", profileFact.approximateDistanceKm(), profileFact.approximateDistanceKm() == null ? "MISSING" : locationFreshness(profileFact)));
        BigDecimal lastActiveAgeHours = lastActiveAgeHours(profileFact.lastActiveAt());
        values.add(numeric("last_active_age_hours", lastActiveAgeHours, lastActiveAgeHours == null ? "MISSING" : profileFreshness(profileFact)));
        values.add(numeric("profile_completeness_score", profileFact.profileCompletenessScore(), profileFact.profileCompletenessScore() == null ? "MISSING" : profileFreshness(profileFact)));
        values.add(text("safety_state", profileFact.safetyState(), safetyFreshness(profileFact)));
        values.add(json("candidate_source_set", Map.of("sourceTypes", retrievalFact.sourceTypes()), "FRESH"));
        values.add(json("retrieval_source_reason_json", retrievalFact.sourceReasons(), "FRESH"));
        values.add(text("location_cluster", cluster(profileFact), profileFact.approximateDistanceKm() == null ? "MISSING" : locationFreshness(profileFact)));
        values.add(text("dominant_interest", dominantInterest, dominantInterest == null ? "MISSING" : "FRESH"));
        values.add(numeric("is_new_or_less_connected", bool(graphFact.mutualCount() + graphFact.commonNeighbourCount() <= 1), "FRESH"));

        int staleFeatureCount = (int) values.stream().filter(value -> "STALE".equals(value.freshnessStatus())).count();
        int missingRequiredFeatureCount = (int) values.stream()
            .filter(value -> REQUIRED_FEATURES.contains(value.featureKey()))
            .filter(value -> "MISSING".equals(value.freshnessStatus()))
            .count();
        String overallFreshness = "FRESH";
        if (missingRequiredFeatureCount > 0) {
            overallFreshness = "MISSING";
        } else if (staleFeatureCount > 0) {
            overallFreshness = "STALE";
        }
        return new FeatureMaterialization(values, staleFeatureCount, missingRequiredFeatureCount, overallFreshness);
    }

    private CandidateFeatureValue numeric(String key, BigDecimal value, String freshness) {
        return new CandidateFeatureValue(key, value, null, null, freshness, null);
    }

    private CandidateFeatureValue text(String key, String value, String freshness) {
        return new CandidateFeatureValue(key, null, value, null, freshness, null);
    }

    private CandidateFeatureValue json(String key, Map<String, Object> value, String freshness) {
        return new CandidateFeatureValue(key, null, null, value, freshness, null);
    }

    private BigDecimal bool(boolean value) {
        return value ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    private boolean hasAnySource(List<String> sourceTypes, List<String> expectedSourceTypes) {
        return sourceTypes.stream().anyMatch(expectedSourceTypes::contains);
    }

    private BigDecimal lastActiveAgeHours(OffsetDateTime lastActiveAt) {
        if (lastActiveAt == null) {
            return null;
        }
        long hours = Duration.between(lastActiveAt, OffsetDateTime.now()).toHours();
        return BigDecimal.valueOf(Math.max(0, hours));
    }

    private String distanceBand(BigDecimal distanceKm) {
        if (distanceKm == null) {
            return null;
        }
        BigDecimal rounded = distanceKm.setScale(3, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.valueOf(25)) <= 0) {
            return "LOCAL";
        }
        if (rounded.compareTo(BigDecimal.valueOf(100)) <= 0) {
            return "REGIONAL";
        }
        return "FAR";
    }

    private String cluster(FeatureSnapshotRepository.CandidateProfileFact profileFact) {
        if (profileFact.locationCity() != null || profileFact.locationRegion() != null || profileFact.locationCountry() != null) {
            return String.join("|",
                nullToBlank(profileFact.locationCountry()),
                nullToBlank(profileFact.locationRegion()),
                nullToBlank(profileFact.locationCity())
            );
        }
        if (profileFact.country() != null || profileFact.region() != null || profileFact.city() != null) {
            return String.join("|", nullToBlank(profileFact.country()), nullToBlank(profileFact.region()), nullToBlank(profileFact.city()));
        }
        return null;
    }

    private String embeddingFreshness(FeatureSnapshotRepository.CandidateProfileFact profileFact) {
        if (profileFact.embeddingStatus() == null || profileFact.embeddingVersion() == null) {
            return "MISSING";
        }
        return "CURRENT".equals(profileFact.embeddingStatus()) ? "FRESH" : "STALE";
    }

    private String safetyFreshness(FeatureSnapshotRepository.CandidateProfileFact profileFact) {
        if (profileFact.safetyState() == null) {
            return "MISSING";
        }
        return profileFact.safetyUpdatedAt() == null ? "STALE" : "FRESH";
    }

    private String profileFreshness(FeatureSnapshotRepository.CandidateProfileFact profileFact) {
        return "ACTIVE".equals(profileFact.status()) ? "FRESH" : "STALE";
    }

    private String locationFreshness(FeatureSnapshotRepository.CandidateProfileFact profileFact) {
        return profileFact.locationUpdatedAt() == null ? "MISSING" : "FRESH";
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private record FeatureMaterialization(
        List<CandidateFeatureValue> values,
        int staleFeatureCount,
        int missingRequiredFeatureCount,
        String overallFreshness
    ) {
    }
}
