package com.matchgraph.api.serving;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.retrieval.CandidateRetrievalRun;
import com.matchgraph.api.retrieval.CandidateRetrievalService;
import com.matchgraph.api.retrieval.CandidateSourceType;
import com.matchgraph.api.retrieval.RunRetrievalRequest;
import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.serving.ServingModels.SourceCallResult;
import com.matchgraph.api.serving.ServingModels.SurfaceConfig;

import org.springframework.stereotype.Service;

@Service
public class CandidateSourceRouter {

    private final CandidateRetrievalService retrievalService;
    private final CandidateSourceBudgetService budgetService;
    private final CandidateSourceHealthService healthService;

    public CandidateSourceRouter(CandidateRetrievalService retrievalService, CandidateSourceBudgetService budgetService, CandidateSourceHealthService healthService) {
        this.retrievalService = retrievalService;
        this.budgetService = budgetService;
        this.healthService = healthService;
    }

    public List<SourceCallResult> route(UUID profileId, SurfaceConfig surface, boolean simulateTimeout) {
        Map<String, Integer> budgets = budgetService.budgets(surface);
        Map<CandidateSourceType, Integer> retrievalBudgets = new java.util.EnumMap<>(CandidateSourceType.class);
        for (Map.Entry<String, Integer> entry : budgets.entrySet()) {
            candidateSourceType(entry.getKey()).ifPresent(type -> retrievalBudgets.put(type, entry.getValue()));
        }
        CandidateRetrievalRun run = retrievalService.run(profileId, new RunRetrievalRequest(surface.resultSize() * 3, retrievalBudgets, false));
        List<SourceCallResult> results = new ArrayList<>();
        for (String source : surface.allowedSources()) {
            boolean timeout = simulateTimeout && "VECTOR_SIMILARITY".equals(source);
            String fallback = timeout ? "GRAPH_MUTUALS" : null;
            List<CandidateItem> candidates = timeout ? List.of() : run.candidates().stream()
                .filter(candidate -> candidate.sourceTypes().stream().anyMatch(type -> type.name().equals(source)))
                .sorted(Comparator.comparingInt(candidate -> candidate.sourceRank()))
                .limit(budgets.getOrDefault(source, 3))
                .map(candidate -> new CandidateItem(candidate.candidateProfileId(), source, candidate.sourceScore() == null ? java.math.BigDecimal.ZERO : candidate.sourceScore(), List.of("source:" + source), candidate.excluded(), candidate.exclusionReason()))
                .toList();
            results.add(new SourceCallResult(source, timeout ? 200 : 20, candidates.size(), timeout, timeout, timeout, fallback, timeout ? "source timeout simulated for test/demo" : null, candidates));
        }
        return results;
    }

    private java.util.Optional<CandidateSourceType> candidateSourceType(String source) {
        try {
            return java.util.Optional.of(CandidateSourceType.valueOf(source));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }
}
