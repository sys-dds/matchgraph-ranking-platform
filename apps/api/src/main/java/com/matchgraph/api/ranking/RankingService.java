package com.matchgraph.api.ranking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.matchgraph.api.features.CandidateFeatureSnapshot;
import com.matchgraph.api.features.CandidateFeatureValue;
import com.matchgraph.api.features.FeatureSnapshotRun;
import com.matchgraph.api.features.FeatureSnapshotService;
import com.matchgraph.api.profile.ProfileService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RankingService {

    private static final int DEFAULT_LIMIT = 20;

    private final RankingRepository rankingRepository;
    private final FeatureSnapshotService featureSnapshotService;
    private final ProfileService profileService;

    public RankingService(RankingRepository rankingRepository, FeatureSnapshotService featureSnapshotService, ProfileService profileService) {
        this.rankingRepository = rankingRepository;
        this.featureSnapshotService = featureSnapshotService;
        this.profileService = profileService;
    }

    @Transactional
    public RankingDecision run(UUID profileId, UUID featureSnapshotRunId, String requestedVersion, Integer requestedLimit, String decisionType) {
        if (featureSnapshotRunId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "featureSnapshotRunId is required");
        }
        profileService.requireExists(profileId);
        FeatureSnapshotRun snapshotRun = featureSnapshotService.get(profileId, featureSnapshotRunId);
        RankingVersion version = rankingRepository.version(requestedVersion);
        int limit = sanitizeLimit(requestedLimit);

        Set<UUID> recentlySeen = rankingRepository.recentlySeenCandidateIds(profileId).stream().collect(Collectors.toSet());
        List<ScoredCandidate> baseScored = snapshotRun.candidates().stream()
            .map(candidate -> score(candidate, version.policy()))
            .filter(candidate -> !candidate.blocked())
            .sorted(Comparator
                .comparing(ScoredCandidate::baseScore).reversed()
                .thenComparing(candidate -> candidate.snapshot().candidateProfileId().toString()))
            .toList();
        List<ScoredCandidate> scored = applyDiversityAndExploration(baseScored, version.policy(), recentlySeen).stream()
            .sorted(Comparator
                .comparing(ScoredCandidate::finalScore).reversed()
                .thenComparing(candidate -> candidate.snapshot().candidateProfileId().toString()))
            .limit(limit)
            .toList();

        List<UUID> candidatePool = snapshotRun.candidates().stream()
            .map(CandidateFeatureSnapshot::candidateProfileId)
            .toList();
        UUID decisionLogId = rankingRepository.createDecision(
            profileId,
            snapshotRun.retrievalRunId(),
            snapshotRun.id(),
            version.versionKey(),
            decisionType == null ? "RANKING_RUN" : decisionType,
            snapshotRun.candidates().size(),
            scored.size(),
            candidatePool
        );

        int position = 1;
        for (ScoredCandidate candidate : scored) {
            RankingDecisionItem item = new RankingDecisionItem(
                candidate.snapshot().candidateProfileId(),
                candidate.snapshot().id(),
                position++,
                candidate.baseScore(),
                candidate.finalScore(),
                candidate.reasons(),
                candidate.diversityAdjustments(),
                candidate.snapshot().sourceTypes(),
                null
            );
            rankingRepository.insertItem(decisionLogId, item);
        }
        return get(profileId, decisionLogId);
    }

    public RankingDecision get(UUID profileId, UUID decisionLogId) {
        profileService.requireExists(profileId);
        return rankingRepository.findDecision(profileId, decisionLogId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ranking decision not found"));
    }

    public RankingDecision get(UUID decisionLogId) {
        return rankingRepository.findDecision(decisionLogId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ranking decision not found"));
    }

    public RankingDecision replay(UUID decisionLogId) {
        RankingDecision decision = get(decisionLogId);
        rankingRepository.version(decision.rankingVersion());
        featureSnapshotService.get(decision.profileId(), decision.featureSnapshotRunId());
        return decision;
    }

    private ScoredCandidate score(CandidateFeatureSnapshot snapshot, RankingPolicy policy) {
        Map<String, CandidateFeatureValue> values = valuesByKey(snapshot.values());
        List<RankingReason> reasons = new ArrayList<>();

        add(reasons, "shared_interests", numeric(values, "shared_interest_count").multiply(weight(policy, "shared_interest_count")), "Shared interest overlap");
        BigDecimal graphDistance = numericOrNull(values, "graph_distance");
        BigDecimal graphCloseness = graphDistance == null || graphDistance.signum() == 0
            ? BigDecimal.ZERO
            : BigDecimal.ONE.divide(graphDistance, 6, RoundingMode.HALF_UP).multiply(weight(policy, "graph_closeness"));
        add(reasons, "graph_closeness", graphCloseness, "Closer graph relationships rank higher");
        add(reasons, "mutual_count", numeric(values, "mutual_count").multiply(weight(policy, "mutual_count")), "Mutual graph connections");
        add(reasons, "common_neighbour_count", numeric(values, "common_neighbour_count").multiply(weight(policy, "common_neighbour_count")), "Common graph neighbours");

        BigDecimal vectorDistance = numericOrNull(values, "vector_distance");
        BigDecimal vectorSimilarity = vectorDistance == null ? BigDecimal.ZERO : BigDecimal.ONE.subtract(vectorDistance).max(BigDecimal.ZERO);
        add(reasons, "vector_similarity", vectorSimilarity.multiply(weight(policy, "vector_similarity")), "Embedding similarity from snapshot");
        add(reasons, "location_distance", locationScore(text(values, "distance_band")).multiply(weight(policy, "location_distance")), "Location distance band");
        add(reasons, "recent_activity", recentActivityScore(numericOrNull(values, "last_active_age_hours")).multiply(weight(policy, "recent_activity")), "Recent candidate activity");
        add(reasons, "profile_completeness_score", numeric(values, "profile_completeness_score").multiply(weight(policy, "profile_completeness_score")), "Profile completeness");

        String safetyState = text(values, "safety_state");
        boolean blocked = "BLOCKED".equals(safetyState);
        BigDecimal safetyPenalty = blocked ? weight(policy, "safety_penalty") : ("LIMITED".equals(safetyState) ? BigDecimal.valueOf(-0.5) : BigDecimal.ZERO);
        add(reasons, "safety_penalty", safetyPenalty, "Safety state penalty");
        add(reasons, "source_diversity_bonus", numeric(values, "source_count").multiply(weight(policy, "source_diversity_bonus")), "Multiple retrieval sources");

        BigDecimal baseScore = reasons.stream()
            .map(RankingReason::scoreDelta)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(6, RoundingMode.HALF_UP);
        return new ScoredCandidate(snapshot, baseScore, baseScore, reasons, List.of(), blocked);
    }

    private List<ScoredCandidate> applyDiversityAndExploration(List<ScoredCandidate> candidates, RankingPolicy policy, Set<UUID> recentlySeen) {
        List<ScoredCandidate> adjusted = new ArrayList<>();
        UUID coldStartCandidate = candidates.stream()
            .filter(candidate -> candidate.snapshot().sourceTypes().contains("COLD_START"))
            .map(candidate -> candidate.snapshot().candidateProfileId())
            .findFirst()
            .orElse(null);
        UUID vectorDiverseCandidate = candidates.stream()
            .filter(candidate -> numericOrNull(valuesByKey(candidate.snapshot().values()), "vector_distance") != null)
            .max(Comparator
                .comparing((ScoredCandidate candidate) -> numericOrNull(valuesByKey(candidate.snapshot().values()), "vector_distance"))
                .thenComparing(candidate -> candidate.snapshot().candidateProfileId().toString()))
            .map(candidate -> candidate.snapshot().candidateProfileId())
            .orElse(null);
        UUID explorationCandidate = candidates.stream()
            .filter(candidate -> BigDecimal.ONE.compareTo(numeric(valuesByKey(candidate.snapshot().values()), "is_new_or_less_connected")) == 0)
            .map(candidate -> candidate.snapshot().candidateProfileId())
            .findFirst()
            .orElse(null);

        Map<String, Integer> locationCounts = new LinkedHashMap<>();
        Map<String, Integer> interestCounts = new LinkedHashMap<>();
        int maxLocationCluster = intSetting(policy, "max_location_cluster_per_page", 3);
        int maxInterestCluster = intSetting(policy, "max_interest_cluster_per_page", 3);
        BigDecimal recentlySeenPenalty = decimalSetting(policy, "recently_seen_penalty", BigDecimal.valueOf(-4));

        for (ScoredCandidate candidate : candidates) {
            Map<String, CandidateFeatureValue> values = valuesByKey(candidate.snapshot().values());
            List<RankingReason> adjustments = new ArrayList<>();
            String locationCluster = text(values, "location_cluster");
            if (locationCluster != null) {
                int count = locationCounts.getOrDefault(locationCluster, 0);
                if (count >= maxLocationCluster) {
                    adjustments.add(new RankingReason("location_diversity_cap", BigDecimal.valueOf(-2).setScale(6, RoundingMode.HALF_UP), "Location cluster cap for page"));
                }
                locationCounts.put(locationCluster, count + 1);
            }
            String dominantInterest = text(values, "dominant_interest");
            if (dominantInterest != null) {
                int count = interestCounts.getOrDefault(dominantInterest, 0);
                if (count >= maxInterestCluster) {
                    adjustments.add(new RankingReason("interest_diversity_cap", BigDecimal.valueOf(-1.5).setScale(6, RoundingMode.HALF_UP), "Dominant interest cluster cap for page"));
                }
                interestCounts.put(dominantInterest, count + 1);
            }
            if (candidate.snapshot().candidateProfileId().equals(coldStartCandidate)) {
                adjustments.add(new RankingReason("cold_start_slot", BigDecimal.valueOf(0.75).setScale(6, RoundingMode.HALF_UP), "Reserved boost for cold-start candidate"));
            }
            if (candidate.snapshot().candidateProfileId().equals(vectorDiverseCandidate)) {
                adjustments.add(new RankingReason("vector_diverse_slot", BigDecimal.valueOf(0.60).setScale(6, RoundingMode.HALF_UP), "Reserved boost for vector-diverse candidate"));
            }
            if (candidate.snapshot().candidateProfileId().equals(explorationCandidate)) {
                adjustments.add(new RankingReason("exploration_slot", BigDecimal.valueOf(0.50).setScale(6, RoundingMode.HALF_UP), "Reserved boost for new or less-connected candidate"));
            }
            if (recentlySeen.contains(candidate.snapshot().candidateProfileId())) {
                adjustments.add(new RankingReason("recently_seen_penalty", recentlySeenPenalty.setScale(6, RoundingMode.HALF_UP), "Penalty for recently seen candidate"));
            }

            BigDecimal adjustmentTotal = adjustments.stream()
                .map(RankingReason::scoreDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            adjusted.add(new ScoredCandidate(
                candidate.snapshot(),
                candidate.baseScore(),
                candidate.baseScore().add(adjustmentTotal).setScale(6, RoundingMode.HALF_UP),
                candidate.reasons(),
                adjustments,
                candidate.blocked()
            ));
        }
        return adjusted;
    }

    private Map<String, CandidateFeatureValue> valuesByKey(List<CandidateFeatureValue> values) {
        Map<String, CandidateFeatureValue> byKey = new LinkedHashMap<>();
        for (CandidateFeatureValue value : values) {
            byKey.put(value.featureKey(), value);
        }
        return byKey;
    }

    private void add(List<RankingReason> reasons, String key, BigDecimal delta, String explanation) {
        reasons.add(new RankingReason(key, delta.setScale(6, RoundingMode.HALF_UP), explanation));
    }

    private BigDecimal weight(RankingPolicy policy, String key) {
        return policy.signals().getOrDefault(key, BigDecimal.ZERO);
    }

    private BigDecimal numeric(Map<String, CandidateFeatureValue> values, String key) {
        BigDecimal value = numericOrNull(values, key);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal numericOrNull(Map<String, CandidateFeatureValue> values, String key) {
        CandidateFeatureValue value = values.get(key);
        return value == null ? null : value.numericValue();
    }

    private String text(Map<String, CandidateFeatureValue> values, String key) {
        CandidateFeatureValue value = values.get(key);
        return value == null ? null : value.textValue();
    }

    private BigDecimal locationScore(String distanceBand) {
        if ("LOCAL".equals(distanceBand)) {
            return BigDecimal.ONE;
        }
        if ("REGIONAL".equals(distanceBand)) {
            return BigDecimal.valueOf(0.5);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal recentActivityScore(BigDecimal ageHours) {
        if (ageHours == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal remaining = BigDecimal.valueOf(168).subtract(ageHours).max(BigDecimal.ZERO);
        return remaining.divide(BigDecimal.valueOf(168), 6, RoundingMode.HALF_UP);
    }

    private int intSetting(RankingPolicy policy, String key, int defaultValue) {
        Object value = policy.diversity().get(key);
        return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
    }

    private BigDecimal decimalSetting(RankingPolicy policy, String key, BigDecimal defaultValue) {
        Object value = policy.diversity().get(key);
        return value == null ? defaultValue : new BigDecimal(String.valueOf(value));
    }

    private int sanitizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        return limit;
    }

    private record ScoredCandidate(
        CandidateFeatureSnapshot snapshot,
        BigDecimal baseScore,
        BigDecimal finalScore,
        List<RankingReason> reasons,
        List<RankingReason> diversityAdjustments,
        boolean blocked
    ) {
    }
}
