package com.matchgraph.api.ranking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.matchgraph.api.features.FeatureRepository;
import com.matchgraph.api.features.FeatureResponse;
import com.matchgraph.api.feed.ItemResponse;
import com.matchgraph.api.graph.GraphEdge;
import com.matchgraph.api.graph.GraphEdgeRepository;
import org.springframework.stereotype.Service;

@Service
public class BaselineRankingService {

    private static final BigDecimal BASE_SCORE = BigDecimal.ONE;
    private static final BigDecimal VIEWED_PENALTY = BigDecimal.valueOf(-1);
    private static final BigDecimal LIKED_BOOST = BigDecimal.valueOf(4);
    private static final BigDecimal SAVED_BOOST = BigDecimal.valueOf(5);
    private static final BigDecimal DISLIKED_PENALTY = BigDecimal.valueOf(-5);

    private final FeatureRepository featureRepository;
    private final GraphEdgeRepository graphEdgeRepository;

    public BaselineRankingService(FeatureRepository featureRepository, GraphEdgeRepository graphEdgeRepository) {
        this.featureRepository = featureRepository;
        this.graphEdgeRepository = graphEdgeRepository;
    }

    public List<RankedCandidate> rank(UUID profileId, List<ItemResponse> candidates, int limit) {
        Map<String, FeatureResponse> profileFeatures = featureRepository.findProfileFeatures(profileId).stream()
            .collect(Collectors.toMap(this::featureKey, feature -> feature, (left, right) -> left));
        Map<UUID, List<GraphEdge>> edgesByItem = graphEdgeRepository.findForProfile(profileId).stream()
            .collect(Collectors.groupingBy(GraphEdge::targetItemId));

        return candidates.stream()
            .map(candidate -> scoreCandidate(candidate, profileFeatures, edgesByItem.getOrDefault(candidate.id(), List.of())))
            .sorted(Comparator
                .comparing(RankedCandidate::score, Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.item().createdAt(), Comparator.reverseOrder())
                .thenComparing(RankedCandidate::itemId))
            .limit(limit)
            .toList();
    }

    private RankedCandidate scoreCandidate(ItemResponse candidate, Map<String, FeatureResponse> profileFeatures, List<GraphEdge> graphEdges) {
        BigDecimal score = BASE_SCORE;
        List<String> reasons = new ArrayList<>();
        reasons.add("base:1.0");

        for (FeatureResponse itemFeature : featureRepository.findItemFeatures(candidate.id())) {
            FeatureResponse profileFeature = profileFeatures.get(featureKey(itemFeature));
            if (profileFeature != null) {
                BigDecimal boost = profileFeature.weight().multiply(itemFeature.weight()).setScale(4, RoundingMode.HALF_UP);
                score = score.add(boost);
                reasons.add("feature:%s=%s:+%s".formatted(itemFeature.featureKey(), itemFeature.featureValue(), reasonNumber(boost)));
            }
        }

        for (GraphEdge edge : graphEdges) {
            BigDecimal delta = interactionDelta(edge.edgeType());
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                score = score.add(delta);
                reasons.add("interaction:%s:%s%s".formatted(edgeLabel(edge.edgeType()), delta.signum() >= 0 ? "+" : "", reasonNumber(delta)));
            }
        }

        BigDecimal freshness = freshnessBoost(candidate.createdAt());
        if (freshness.compareTo(BigDecimal.ZERO) > 0) {
            score = score.add(freshness);
            reasons.add("freshness:+%s".formatted(reasonNumber(freshness)));
        }

        return new RankedCandidate(candidate.id(), candidate, score.setScale(4, RoundingMode.HALF_UP), new ScoreExplanation(reasons));
    }

    private BigDecimal interactionDelta(String edgeType) {
        return switch (edgeType) {
            case "VIEWED" -> VIEWED_PENALTY;
            case "LIKED" -> LIKED_BOOST;
            case "SAVED" -> SAVED_BOOST;
            case "DISLIKED" -> DISLIKED_PENALTY;
            default -> BigDecimal.ZERO;
        };
    }

    private String edgeLabel(String edgeType) {
        return switch (edgeType) {
            case "VIEWED" -> "viewed";
            case "LIKED" -> "liked";
            case "SAVED" -> "saved";
            case "DISLIKED" -> "disliked";
            case "CLICKED" -> "clicked";
            default -> edgeType.toLowerCase();
        };
    }

    private BigDecimal freshnessBoost(OffsetDateTime createdAt) {
        long ageHours = Math.max(0, Duration.between(createdAt, OffsetDateTime.now()).toHours());
        return BigDecimal.valueOf(0.1d / (ageHours + 1)).setScale(4, RoundingMode.HALF_UP);
    }

    private String featureKey(FeatureResponse feature) {
        return feature.featureKey() + "=" + feature.featureValue();
    }

    private String reasonNumber(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
