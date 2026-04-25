package com.matchgraph.api.shadow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.matchgraph.api.ranking.RankingDecision;
import com.matchgraph.api.ranking.RankingDecisionItem;
import com.matchgraph.api.ranking.RankingReplayItem;
import com.matchgraph.api.ranking.RankingService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShadowRankingService {

    private final ShadowRankingRepository shadowRankingRepository;
    private final RankingService rankingService;

    public ShadowRankingService(ShadowRankingRepository shadowRankingRepository, RankingService rankingService) {
        this.shadowRankingRepository = shadowRankingRepository;
        this.rankingService = rankingService;
    }

    @Transactional
    public ShadowRankingRun run(ShadowRankingRunRequest request) {
        if (request == null || request.baselineDecisionLogId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baselineDecisionLogId is required");
        }
        if (request.challengerRankingVersion() == null || request.challengerRankingVersion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "challengerRankingVersion is required");
        }
        RankingDecision baseline = rankingService.get(request.baselineDecisionLogId());
        int limit = request.limit() == null ? baseline.servedCount() : Math.max(1, Math.min(100, request.limit()));
        List<RankingReplayItem> challenger = rankingService.rankStoredSnapshot(
            baseline.profileId(),
            baseline.featureSnapshotRunId(),
            request.challengerRankingVersion().trim(),
            limit,
            baseline.rankingContext()
        );

        UUID runId = shadowRankingRepository.createRun(
            baseline.profileId(),
            baseline.id(),
            baseline.rankingVersion(),
            request.challengerRankingVersion().trim(),
            baseline.featureSnapshotRunId(),
            baseline.rankingContext()
        );
        List<ShadowRankingItem> items = compare(runId, baseline.items(), challenger);
        items.forEach(item -> shadowRankingRepository.insertItem(runId, item));
        shadowRankingRepository.completeRun(runId, summary(baseline, request.challengerRankingVersion().trim(), challenger, items, limit));
        return get(runId);
    }

    public ShadowRankingRun get(UUID shadowRunId) {
        return shadowRankingRepository.findRun(shadowRunId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "shadow ranking run not found"));
    }

    private List<ShadowRankingItem> compare(UUID runId, List<RankingDecisionItem> championItems, List<RankingReplayItem> challengerItems) {
        Map<UUID, RankingDecisionItem> champion = championItems.stream()
            .collect(Collectors.toMap(RankingDecisionItem::candidateProfileId, Function.identity()));
        Map<UUID, RankingReplayItem> challenger = challengerItems.stream()
            .collect(Collectors.toMap(RankingReplayItem::candidateProfileId, Function.identity()));
        LinkedHashSet<UUID> candidateIds = new LinkedHashSet<>();
        championItems.stream()
            .sorted(Comparator.comparing(RankingDecisionItem::position))
            .map(RankingDecisionItem::candidateProfileId)
            .forEach(candidateIds::add);
        challengerItems.stream()
            .sorted(Comparator.comparing(RankingReplayItem::position))
            .map(RankingReplayItem::candidateProfileId)
            .forEach(candidateIds::add);

        List<ShadowRankingItem> items = new ArrayList<>();
        for (UUID candidateId : candidateIds) {
            RankingDecisionItem championItem = champion.get(candidateId);
            RankingReplayItem challengerItem = challenger.get(candidateId);
            Integer championPosition = championItem == null ? null : championItem.position();
            Integer challengerPosition = challengerItem == null ? null : challengerItem.position();
            BigDecimal championScore = championItem == null ? null : championItem.finalScore();
            BigDecimal challengerScore = challengerItem == null ? null : challengerItem.finalScore();
            items.add(new ShadowRankingItem(
                UUID.randomUUID(),
                runId,
                candidateId,
                championPosition,
                challengerPosition,
                championScore,
                challengerScore,
                positionDelta(championPosition, challengerPosition),
                scoreDelta(championScore, challengerScore),
                reasonDelta(championItem, challengerItem),
                null
            ));
        }
        return items;
    }

    private Map<String, Object> summary(
        RankingDecision baseline,
        String challengerRankingVersion,
        List<RankingReplayItem> challenger,
        List<ShadowRankingItem> items,
        int limit
    ) {
        long improved = items.stream()
            .filter(item -> item.positionDelta() != null && item.positionDelta() > 0)
            .count();
        long degraded = items.stream()
            .filter(item -> item.positionDelta() != null && item.positionDelta() < 0)
            .count();
        long overlap = items.stream()
            .filter(item -> item.championPosition() != null && item.challengerPosition() != null)
            .filter(item -> item.championPosition() <= limit && item.challengerPosition() <= limit)
            .count();
        BigDecimal topKOverlap = BigDecimal.valueOf(overlap)
            .divide(BigDecimal.valueOf(Math.max(1, limit)), 6, RoundingMode.HALF_UP);
        BigDecimal averagePositionDelta = items.stream()
            .filter(item -> item.positionDelta() != null)
            .map(item -> BigDecimal.valueOf(item.positionDelta()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(Math.max(1, items.stream().filter(item -> item.positionDelta() != null).count())), 6, RoundingMode.HALF_UP);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("baselineDecisionLogId", baseline.id().toString());
        summary.put("profileId", baseline.profileId().toString());
        summary.put("featureSnapshotRunId", baseline.featureSnapshotRunId().toString());
        summary.put("championRankingVersion", baseline.rankingVersion());
        summary.put("challengerRankingVersion", challengerRankingVersion);
        summary.put("challengerItemCount", challenger.size());
        summary.put("challengerImprovedCount", improved);
        summary.put("challengerDegradedCount", degraded);
        summary.put("topKOverlap", topKOverlap);
        summary.put("averagePositionDelta", averagePositionDelta);
        summary.put("feedMutation", false);
        summary.put("retrievalRerun", false);
        summary.put("featureSnapshotRecreated", false);
        return summary;
    }

    private Integer positionDelta(Integer championPosition, Integer challengerPosition) {
        if (championPosition == null || challengerPosition == null) {
            return null;
        }
        return championPosition - challengerPosition;
    }

    private BigDecimal scoreDelta(BigDecimal championScore, BigDecimal challengerScore) {
        if (championScore == null || challengerScore == null) {
            return null;
        }
        return challengerScore.subtract(championScore);
    }

    private Map<String, Object> reasonDelta(RankingDecisionItem championItem, RankingReplayItem challengerItem) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("championReasons", championItem == null ? List.of() : championItem.reasons());
        delta.put("challengerReasons", challengerItem == null ? List.of() : challengerItem.reasons());
        delta.put("championDiversityAdjustments", championItem == null ? List.of() : championItem.diversityAdjustments());
        delta.put("challengerDiversityAdjustments", challengerItem == null ? List.of() : challengerItem.diversityAdjustments());
        return delta;
    }
}
