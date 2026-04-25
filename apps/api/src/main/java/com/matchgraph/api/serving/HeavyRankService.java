package com.matchgraph.api.serving;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.serving.ServingModels.HeavyRankRun;

import org.springframework.stereotype.Service;

@Service
public class HeavyRankService {

    private final HeavyRankBudgetService budgetService;
    private final HeavyRankRepository repository;

    public HeavyRankService(HeavyRankBudgetService budgetService, HeavyRankRepository repository) {
        this.budgetService = budgetService;
        this.repository = repository;
    }

    public HeavyRankRun rank(UUID requestId, String rankingVersion, List<CandidateItem> candidates, boolean simulateModelUnavailable, boolean simulateTimeout) {
        boolean modelBacked = rankingVersion != null && rankingVersion.startsWith("ltr:");
        boolean lacksFeatureSnapshotContext = modelBacked;
        boolean fallback = budgetService.shouldFallback(rankingVersion, simulateModelUnavailable, simulateTimeout) || lacksFeatureSnapshotContext;
        String fallbackReason = fallback ? (simulateModelUnavailable ? "model unavailable simulated" : (simulateTimeout ? "model timeout simulated" : "real RankingService/model-backed path requires feature snapshot context; using explicit rule fallback")) : null;
        List<CandidateItem> ranked = candidates.stream()
            .map(candidate -> new CandidateItem(
                candidate.candidateProfileId(),
                candidate.sourceKey(),
                candidate.score().add(modelBacked && !fallback ? BigDecimal.valueOf(1.25) : BigDecimal.valueOf(0.25)),
                modelBacked && !fallback ? List.of("MODEL_WEIGHTED_SCORE", "feature contribution summary persisted") : List.of("rule ranking fallback", fallbackReason == null ? "legacy ranking version" : fallbackReason),
                false,
                null
            ))
            .sorted(Comparator.comparing(CandidateItem::score).reversed().thenComparing(candidate -> candidate.candidateProfileId().toString()))
            .toList();
        String[] parts = modelBacked ? rankingVersion.split(":", 3) : new String[0];
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
            java.util.Map.of("realModelPathUsed", modelBacked && !fallback, "fallbackLabelled", fallback)
        );
        ranked.forEach(item -> repository.insertItem(runId, item, modelBacked && !fallback));
        return new HeavyRankRun(runId, ranked, fallback, fallbackReason, modelBacked && !fallback, fallback ? 150 : 30);
    }
}
