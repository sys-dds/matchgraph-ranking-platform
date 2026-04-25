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

    public HeavyRankService(HeavyRankBudgetService budgetService) {
        this.budgetService = budgetService;
    }

    public HeavyRankRun rank(String rankingVersion, List<CandidateItem> candidates, boolean simulateModelUnavailable, boolean simulateTimeout) {
        boolean modelBacked = rankingVersion != null && rankingVersion.startsWith("ltr:");
        boolean fallback = budgetService.shouldFallback(rankingVersion, simulateModelUnavailable, simulateTimeout);
        String fallbackReason = fallback ? (simulateModelUnavailable ? "model unavailable simulated" : "model timeout simulated") : null;
        List<CandidateItem> ranked = candidates.stream()
            .map(candidate -> new CandidateItem(
                candidate.candidateProfileId(),
                candidate.sourceKey(),
                candidate.score().add(modelBacked && !fallback ? BigDecimal.valueOf(1.25) : BigDecimal.valueOf(0.25)),
                modelBacked && !fallback ? List.of("MODEL_WEIGHTED_SCORE", "feature contribution summary persisted") : List.of("rule ranking fallback"),
                false,
                null
            ))
            .sorted(Comparator.comparing(CandidateItem::score).reversed().thenComparing(candidate -> candidate.candidateProfileId().toString()))
            .toList();
        return new HeavyRankRun(UUID.randomUUID(), ranked, fallback, fallbackReason, modelBacked && !fallback, fallback ? 150 : 30);
    }
}
