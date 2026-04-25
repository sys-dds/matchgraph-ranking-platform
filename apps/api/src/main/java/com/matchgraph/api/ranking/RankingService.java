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
import com.matchgraph.api.features.FeatureSnapshotRepository;
import com.matchgraph.api.features.FeatureSnapshotRun;
import com.matchgraph.api.features.FeatureSnapshotService;
import com.matchgraph.api.ltr.LtrModelArtifact;
import com.matchgraph.api.ltr.LtrModelRegistryService;
import com.matchgraph.api.ltr.LtrModelVersion;
import com.matchgraph.api.profile.ProfileService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RankingService {

    private static final int DEFAULT_LIMIT = 20;

    private final RankingRepository rankingRepository;
    private final FeatureSnapshotRepository featureSnapshotRepository;
    private final FeatureSnapshotService featureSnapshotService;
    private final ProfileService profileService;
    private final LtrModelRegistryService ltrModelRegistryService;

    public RankingService(
        RankingRepository rankingRepository,
        FeatureSnapshotRepository featureSnapshotRepository,
        FeatureSnapshotService featureSnapshotService,
        ProfileService profileService,
        LtrModelRegistryService ltrModelRegistryService
    ) {
        this.rankingRepository = rankingRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.featureSnapshotService = featureSnapshotService;
        this.profileService = profileService;
        this.ltrModelRegistryService = ltrModelRegistryService;
    }

    @Transactional
    public RankingDecision run(UUID profileId, UUID featureSnapshotRunId, String requestedVersion, Integer requestedLimit, String decisionType) {
        return run(profileId, featureSnapshotRunId, requestedVersion, requestedLimit, decisionType, null, null, null, null);
    }

    @Transactional
    public RankingDecision run(
        UUID profileId,
        UUID featureSnapshotRunId,
        String requestedVersion,
        Integer requestedLimit,
        String decisionType,
        String experimentKey,
        String assignedVariant,
        UUID assignmentId,
        Map<String, Object> cacheContext
    ) {
        if (featureSnapshotRunId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "featureSnapshotRunId is required");
        }
        profileService.requireExists(profileId);
        FeatureSnapshotRun snapshotRun = featureSnapshotService.get(profileId, featureSnapshotRunId);
        RankingVersion version = rankingRepository.version(requestedVersion);
        int limit = sanitizeLimit(requestedLimit);
        String normalizedDecisionType = decisionType == null ? "RANKING_RUN" : decisionType;

        Set<UUID> recentlySeen = rankingRepository.recentlySeenCandidateIds(profileId).stream().collect(Collectors.toSet());
        ModelRankingSpec modelSpec = modelSpec(version.versionKey());
        List<ScoredCandidate> scored = computeRanking(snapshotRun, version, limit, recentlySeen, modelSpec);

        List<UUID> candidatePool = snapshotRun.candidates().stream()
            .map(CandidateFeatureSnapshot::candidateProfileId)
            .toList();
        Map<String, Object> rankingContext = rankingContext(
            recentlySeen,
            limit,
            version.versionKey(),
            normalizedDecisionType,
            experimentKey,
            assignedVariant,
            assignmentId,
            cacheContext,
            snapshotRun,
            modelSpec
        );
        UUID decisionLogId = rankingRepository.createDecision(
            profileId,
            snapshotRun.retrievalRunId(),
            snapshotRun.id(),
            version.versionKey(),
            normalizedDecisionType,
            snapshotRun.candidates().size(),
            scored.size(),
            candidatePool,
            rankingContext
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

    public RankingReplayResponse replay(UUID decisionLogId) {
        RankingDecision decision = get(decisionLogId);
        Map<String, Object> context = decision.rankingContext();
        RankingVersion version = rankingRepository.version(stringContext(context, "rankingVersion", decision.rankingVersion()));
        FeatureSnapshotRun snapshotRun = featureSnapshotRepository.findRun(decision.profileId(), decision.featureSnapshotRunId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "feature snapshot run not found"));
        List<ScoredCandidate> replayed = computeRanking(
            snapshotRun,
            version,
            intContext(context, "requestedLimit", decision.servedCount()),
            uuidSetContext(context, "recentlySeenCandidateIds"),
            modelSpec(version.versionKey())
        );
        List<UUID> originalOrder = decision.items().stream()
            .map(RankingDecisionItem::candidateProfileId)
            .toList();
        List<UUID> replayedOrder = replayed.stream()
            .map(candidate -> candidate.snapshot().candidateProfileId())
            .toList();
        return new RankingReplayResponse(
            decision.id(),
            decision.profileId(),
            decision.rankingVersion(),
            decision.featureSnapshotRunId(),
            originalOrder,
            replayedOrder,
            originalOrder.equals(replayedOrder),
            mismatches(originalOrder, replayedOrder),
            replayedItems(replayed)
        );
    }

    public List<RankingReplayItem> rankStoredSnapshot(
        UUID profileId,
        UUID featureSnapshotRunId,
        String rankingVersion,
        int limit,
        Map<String, Object> rankingContext
    ) {
        RankingVersion version = rankingRepository.version(rankingVersion);
        FeatureSnapshotRun snapshotRun = featureSnapshotRepository.findRun(profileId, featureSnapshotRunId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "feature snapshot run not found"));
        return replayedItems(computeRanking(snapshotRun, version, limit, uuidSetContext(rankingContext == null ? Map.of() : rankingContext, "recentlySeenCandidateIds"), modelSpec(version.versionKey())));
    }

    private List<ScoredCandidate> computeRanking(FeatureSnapshotRun snapshotRun, RankingVersion version, int limit, Set<UUID> recentlySeen, ModelRankingSpec modelSpec) {
        List<ScoredCandidate> baseScored = snapshotRun.candidates().stream()
            .map(candidate -> modelSpec == null ? score(candidate, version.policy()) : scoreModel(candidate, modelSpec))
            .filter(candidate -> !candidate.blocked())
            .sorted(Comparator
                .comparing(ScoredCandidate::baseScore).reversed()
                .thenComparing(candidate -> candidate.snapshot().candidateProfileId().toString()))
            .toList();
        return applyDiversityAndExploration(baseScored, version.policy(), recentlySeen).stream()
            .sorted(Comparator
                .comparing(ScoredCandidate::finalScore).reversed()
                .thenComparing(candidate -> candidate.snapshot().candidateProfileId().toString()))
            .limit(limit)
            .toList();
    }

    private Map<String, Object> rankingContext(
        Set<UUID> recentlySeen,
        int requestedLimit,
        String rankingVersion,
        String decisionType,
        String experimentKey,
        String assignedVariant,
        UUID assignmentId,
        Map<String, Object> cacheContext,
        FeatureSnapshotRun snapshotRun,
        ModelRankingSpec modelSpec
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("recentlySeenCandidateIds", recentlySeen.stream().map(UUID::toString).sorted().toList());
        context.put("requestedLimit", requestedLimit);
        context.put("rankingVersion", rankingVersion);
        context.put("decisionType", decisionType);
        context.put("experimentKey", blankToNull(experimentKey));
        context.put("assignedVariant", blankToNull(assignedVariant));
        context.put("assignmentId", assignmentId == null ? null : assignmentId.toString());
        context.put("cacheContext", cacheContext == null ? null : cacheContext);
        context.put("retrievalRunId", snapshotRun.retrievalRunId().toString());
        context.put("featureSnapshotRunId", snapshotRun.id().toString());
        context.put("modelBackedRanking", modelSpec != null);
        if (modelSpec != null) {
            context.put("modelKey", modelSpec.modelKey());
            context.put("versionKey", modelSpec.versionKey());
            context.put("modelVersionId", modelSpec.version().id().toString());
            context.put("featureSchemaVersion", modelSpec.version().featureSchemaVersion());
        }
        return context;
    }

    private Set<UUID> uuidSetContext(Map<String, Object> context, String key) {
        Object raw = context.get(key);
        if (!(raw instanceof List<?> values)) {
            return Set.of();
        }
        return values.stream()
            .map(String::valueOf)
            .map(UUID::fromString)
            .collect(Collectors.toSet());
    }

    private int intContext(Map<String, Object> context, String key, int defaultValue) {
        Object raw = context.get(key);
        if (raw == null) {
            return defaultValue;
        }
        return Integer.parseInt(String.valueOf(raw));
    }

    private String stringContext(Map<String, Object> context, String key, String defaultValue) {
        Object raw = context.get(key);
        String value = raw == null ? null : String.valueOf(raw);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<RankingReplayItem> replayedItems(List<ScoredCandidate> replayed) {
        List<RankingReplayItem> items = new ArrayList<>();
        int position = 1;
        for (ScoredCandidate candidate : replayed) {
            items.add(new RankingReplayItem(
                candidate.snapshot().candidateProfileId(),
                candidate.snapshot().id(),
                position++,
                candidate.baseScore(),
                candidate.finalScore(),
                candidate.reasons(),
                candidate.diversityAdjustments(),
                candidate.snapshot().sourceTypes()
            ));
        }
        return items;
    }

    private List<String> mismatches(List<UUID> originalOrder, List<UUID> replayedOrder) {
        List<String> mismatches = new ArrayList<>();
        int max = Math.max(originalOrder.size(), replayedOrder.size());
        for (int index = 0; index < max; index++) {
            UUID original = index < originalOrder.size() ? originalOrder.get(index) : null;
            UUID replayed = index < replayedOrder.size() ? replayedOrder.get(index) : null;
            if (!java.util.Objects.equals(original, replayed)) {
                mismatches.add("position " + (index + 1) + ": original=" + original + ", replayed=" + replayed);
            }
        }
        return mismatches;
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

    private ScoredCandidate scoreModel(CandidateFeatureSnapshot snapshot, ModelRankingSpec spec) {
        Map<String, CandidateFeatureValue> values = valuesByKey(snapshot.values());
        String safetyState = text(values, "safety_state");
        boolean blocked = "BLOCKED".equals(safetyState);
        List<RankingReason> reasons = new ArrayList<>();
        BigDecimal score = BigDecimal.ZERO;
        List<Map<String, Object>> contributions = new ArrayList<>();
        for (String featureName : spec.artifact().featureNames()) {
            BigDecimal rawValue = numeric(values, featureName);
            BigDecimal normalized = normalize(featureName, rawValue, spec.artifact().normalization());
            BigDecimal weight = number(spec.artifact().weights().get(featureName));
            BigDecimal contribution = normalized.multiply(weight).setScale(6, RoundingMode.HALF_UP);
            score = score.add(contribution);
            contributions.add(Map.of(
                "feature", featureName,
                "snapshotValue", rawValue,
                "normalizedValue", normalized,
                "weight", weight,
                "contribution", contribution
            ));
        }
        if (blocked) {
            score = score.subtract(BigDecimal.valueOf(1000));
        }
        reasons.add(new RankingReason(
            "MODEL_WEIGHTED_SCORE",
            score.setScale(6, RoundingMode.HALF_UP),
            "LTR model weighted score using stored feature snapshot values only; featureContributions=" + contributions
        ));
        return new ScoredCandidate(snapshot, score.setScale(6, RoundingMode.HALF_UP), score.setScale(6, RoundingMode.HALF_UP), reasons, List.of(), blocked);
    }

    private ModelRankingSpec modelSpec(String versionKey) {
        if (versionKey == null || !versionKey.startsWith("ltr:")) {
            return null;
        }
        String[] parts = versionKey.split(":", 3);
        if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LTR ranking version must be ltr:{modelKey}:{versionKey}");
        }
        LtrModelVersion version = ltrModelRegistryService.getVersion(parts[1], parts[2]);
        if ("REJECTED".equals(version.status()) || "RETIRED".equals(version.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LTR model version cannot be used for ranking in state " + version.status());
        }
        LtrModelArtifact artifact;
        try {
            artifact = ltrModelRegistryService.getArtifact(parts[1], parts[2]);
        } catch (ResponseStatusException exception) {
            if ("DRAFT".equals(version.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "DRAFT LTR model version cannot rank without artifact");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LTR model artifact missing for " + versionKey);
        }
        return new ModelRankingSpec(parts[1], parts[2], version, artifact);
    }

    private BigDecimal normalize(String featureName, BigDecimal value, Map<String, Object> normalization) {
        Object raw = normalization.get(featureName);
        if (!(raw instanceof Map<?, ?> map)) {
            return value;
        }
        BigDecimal mean = number(map.containsKey("mean") ? map.get("mean") : "0");
        BigDecimal std = number(map.containsKey("std") ? map.get("std") : "1");
        if (std.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return value.subtract(mean).divide(std, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal number(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value)).setScale(6, RoundingMode.HALF_UP);
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

    private record ModelRankingSpec(String modelKey, String versionKey, LtrModelVersion version, LtrModelArtifact artifact) {
    }
}
