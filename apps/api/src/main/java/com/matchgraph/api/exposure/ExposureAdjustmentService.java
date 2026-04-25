package com.matchgraph.api.exposure;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.matchgraph.api.ranking.RankingDecision;
import com.matchgraph.api.ranking.RankingDecisionItem;
import com.matchgraph.api.retrieval.HardExclusionService;

import org.springframework.stereotype.Service;

@Service
public class ExposureAdjustmentService {

    private final ExposureRepository repository;
    private final HardExclusionService hardExclusionService;

    public ExposureAdjustmentService(ExposureRepository repository, HardExclusionService hardExclusionService) {
        this.repository = repository;
        this.hardExclusionService = hardExclusionService;
    }

    public List<AdjustedRankingItem> adjust(UUID viewerProfileId, RankingDecision decision, UUID feedSnapshotId) {
        return repository.activePolicy()
            .filter(policy -> "ACTIVE".equals(policy.status()))
            .map(policy -> adjusted(viewerProfileId, decision, feedSnapshotId, policy))
            .orElseGet(() -> original(decision.items()));
    }

    private List<AdjustedRankingItem> adjusted(UUID viewerProfileId, RankingDecision decision, UUID feedSnapshotId, ExposureControlPolicy policy) {
        List<AdjustedRankingItem> scored = decision.items().stream()
            .map(item -> adjustment(viewerProfileId, decision.id(), feedSnapshotId, policy, item))
            .sorted(Comparator
                .comparing(AdjustedRankingItem::adjustedScore).reversed()
                .thenComparing(adjusted -> adjusted.item().candidateProfileId().toString()))
            .toList();
        AtomicInteger position = new AtomicInteger(1);
        return scored.stream()
            .map(item -> new AdjustedRankingItem(
                item.item(),
                position.getAndIncrement(),
                item.adjustedScore(),
                item.boostAmount(),
                item.downrankAmount(),
                item.reason()
            ))
            .toList();
    }

    private AdjustedRankingItem adjustment(
        UUID viewerProfileId,
        UUID decisionLogId,
        UUID feedSnapshotId,
        ExposureControlPolicy policy,
        RankingDecisionItem item
    ) {
        BigDecimal boost = BigDecimal.ZERO;
        BigDecimal downrank = BigDecimal.ZERO;
        String reason = "NO_EXPOSURE_ADJUSTMENT";
        boolean hardExcluded = hardExclusionService.exclusionReason(viewerProfileId, item.candidateProfileId()).isPresent();
        int rolling = repository.exposureCount(item.candidateProfileId(), 24 * 7);
        if (!hardExcluded && rolling >= policy.rolling7DayCap()) {
            downrank = policy.overexposureDownrank();
            reason = "OVEREXPOSED_DOWNRANK";
        } else if (!hardExcluded && rolling <= 1) {
            boost = policy.longTailBoost().add(policy.newProfileMinimumBoost());
            reason = "LONG_TAIL_OR_NEW_PROFILE_BOOST";
        } else if (hardExcluded) {
            reason = "HARD_EXCLUSION_OVERRIDE";
        }
        BigDecimal adjustedScore = item.finalScore().add(boost).subtract(downrank);
        if (!"NO_EXPOSURE_ADJUSTMENT".equals(reason)) {
            repository.insertAdjustment(
                policy,
                item.candidateProfileId(),
                viewerProfileId,
                decisionLogId,
                feedSnapshotId,
                reason,
                boost,
                downrank,
                hardExcluded,
                Map.of("rolling7DayExposureCount", rolling, "bounded", true)
            );
        }
        return new AdjustedRankingItem(item, item.position(), adjustedScore, boost, downrank, reason);
    }

    private List<AdjustedRankingItem> original(List<RankingDecisionItem> items) {
        return items.stream()
            .map(item -> new AdjustedRankingItem(item, item.position(), item.finalScore(), BigDecimal.ZERO, BigDecimal.ZERO, "POLICY_DISABLED"))
            .toList();
    }
}
