package com.matchgraph.api.serving;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.serving.ServingModels.HeavyRankRun;
import com.matchgraph.api.streaming.OnlineModelKillSwitchService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class HeavyRankService {

    private final HeavyRankBudgetService budgetService;
    private final HeavyRankRepository repository;
    private final ObjectProvider<OnlineModelKillSwitchService> killSwitchService;

    public HeavyRankService(HeavyRankBudgetService budgetService, HeavyRankRepository repository, ObjectProvider<OnlineModelKillSwitchService> killSwitchService) {
        this.budgetService = budgetService;
        this.repository = repository;
        this.killSwitchService = killSwitchService;
    }

    public HeavyRankRun rank(UUID requestId, String rankingVersion, List<CandidateItem> candidates, boolean simulateModelUnavailable, boolean simulateTimeout) {
        boolean modelBacked = rankingVersion != null && rankingVersion.startsWith("ltr:");
        boolean lacksFeatureSnapshotContext = modelBacked;
        String[] parts = modelBacked ? rankingVersion.split(":", 3) : new String[0];
        boolean killed = parts.length == 3 && killed(parts[1], parts[2]);
        boolean fallback = budgetService.shouldFallback(rankingVersion, simulateModelUnavailable, simulateTimeout) || lacksFeatureSnapshotContext || killed;
        String fallbackReason = fallback ? (killed ? "model killed by online kill switch" : simulateModelUnavailable ? "model unavailable simulated" : (simulateTimeout ? "model timeout simulated" : "real RankingService/model-backed path requires feature snapshot context; using explicit rule fallback")) : null;
        List<CandidateItem> ranked = candidates.stream()
            .map(candidate -> new CandidateItem(
                candidate.candidateProfileId(),
                candidate.sourceKey(),
                candidate.score().add(modelBacked && !fallback ? BigDecimal.valueOf(1.25) : BigDecimal.valueOf(0.25)),
                modelBacked && !fallback
                    ? List.of("MODEL_WEIGHTED_SCORE", "feature contribution summary persisted")
                    : List.of(
                        "RULE_FALLBACK_SCORE_APPROXIMATE",
                        "not model-backed scoring",
                        fallbackReason == null ? "legacy ranking version" : fallbackReason
                    ),
                false,
                null
            ))
            .sorted(Comparator.comparing(CandidateItem::score).reversed().thenComparing(candidate -> candidate.candidateProfileId().toString()))
            .toList();
        UUID runId = repository.createRun(
            requestId,
            rankingVersion,
            modelBacked && !fallback,
            parts.length == 3 ? parts[1] : null,
            parts.length == 3 ? parts[2] : null,
            100,
            fallback,
            fallbackReason,
            fallback ? 150 : 30,
            java.util.Map.of(
                "realModelPathUsed", modelBacked && !fallback,
                "fallbackLabelled", fallback,
                "approximateRuleFallbackScore", fallback,
                "notClaimedAsModelScore", fallback,
                "onlineKillSwitchBlocked", killed
            )
        );
        ranked.forEach(item -> repository.insertItem(runId, item, modelBacked && !fallback));
        return new HeavyRankRun(runId, ranked, fallback, fallbackReason, modelBacked && !fallback, fallback ? 150 : 30);
    }

    private boolean killed(String modelKey, String versionKey) {
        OnlineModelKillSwitchService service = killSwitchService.getIfAvailable();
        return service != null && service.killed(modelKey, versionKey);
    }
}
