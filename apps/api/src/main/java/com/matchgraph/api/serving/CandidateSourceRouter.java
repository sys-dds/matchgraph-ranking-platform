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
import com.matchgraph.api.realtime.AdaptiveSourceRoutingService;
import com.matchgraph.api.realtime.CandidateInvalidationService;
import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.serving.ServingModels.SourceCallResult;
import com.matchgraph.api.serving.ServingModels.SourceRoutingResult;
import com.matchgraph.api.serving.ServingModels.SurfaceConfig;
import com.matchgraph.api.serving.ServingModels.SessionIntentState;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class CandidateSourceRouter {

    private final CandidateRetrievalService retrievalService;
    private final CandidateSourceBudgetService budgetService;
    private final CandidateSourceHealthService healthService;
    private final RecommendationSurfaceRepository repository;
    private final ObjectProvider<CandidateInvalidationService> invalidationService;
    private final ObjectProvider<AdaptiveSourceRoutingService> adaptiveRoutingService;
    private final ObjectProvider<com.matchgraph.api.streaming.SourceHealthService> streamingSourceHealthService;

    public CandidateSourceRouter(CandidateRetrievalService retrievalService, CandidateSourceBudgetService budgetService, CandidateSourceHealthService healthService, RecommendationSurfaceRepository repository, ObjectProvider<CandidateInvalidationService> invalidationService, ObjectProvider<AdaptiveSourceRoutingService> adaptiveRoutingService, ObjectProvider<com.matchgraph.api.streaming.SourceHealthService> streamingSourceHealthService) {
        this.retrievalService = retrievalService;
        this.budgetService = budgetService;
        this.healthService = healthService;
        this.repository = repository;
        this.invalidationService = invalidationService;
        this.adaptiveRoutingService = adaptiveRoutingService;
        this.streamingSourceHealthService = streamingSourceHealthService;
    }

    public SourceRoutingResult route(UUID requestId, UUID profileId, SurfaceConfig surface, UUID sessionId, SessionIntentState intent, boolean simulateTimeout) {
        Map<String, Integer> baseBudgets = budgetService.budgets(surface, intent);
        AdaptiveSourceRoutingService adaptive = adaptiveRoutingService.getIfAvailable();
        Map<String, Integer> adaptiveBudgets = adaptive == null ? baseBudgets : adaptive.adapt(profileId, sessionId, baseBudgets, intent);
        Map<String, Integer> budgets = applyStreamingHealth(adaptiveBudgets);
        UUID planId = repository.createSourceRoutingPlan(requestId, surface, sessionId, Map.of(
            "budgets", budgets,
            "baseBudgets", baseBudgets,
            "adaptiveBudgets", adaptiveBudgets,
            "sessionIntentWeights", intent == null ? Map.of() : intent.sourceWeights(),
            "adaptive", true
        ));
        Map<CandidateSourceType, Integer> retrievalBudgets = new java.util.EnumMap<>(CandidateSourceType.class);
        for (Map.Entry<String, Integer> entry : budgets.entrySet()) {
            candidateSourceType(entry.getKey()).ifPresent(type -> retrievalBudgets.put(type, entry.getValue()));
        }
        CandidateRetrievalRun run = retrievalService.run(profileId, new RunRetrievalRequest(surface.resultSize() * 3, retrievalBudgets, false));
        List<SourceCallResult> results = new ArrayList<>();
        String timeoutSource = surface.allowedSources().contains("VECTOR_SIMILARITY") ? "VECTOR_SIMILARITY" : surface.allowedSources().getFirst();
        for (String source : surface.allowedSources()) {
            boolean timeout = simulateTimeout && timeoutSource.equals(source);
            String fallback = timeout ? "GRAPH_MUTUALS" : null;
            String health = healthService.health(source, timeout);
            repository.insertSourceRoutingPlanItem(planId, source, sourcePriority(source), budgets.getOrDefault(source, 1), timeout ? 25 : 75, fallback, health, java.math.BigDecimal.valueOf("HEALTHY".equals(health) ? 1.0 : 0.2), healthService.detail(source, timeout));
            List<CandidateItem> candidates = timeout ? List.of() : run.candidates().stream()
                .filter(candidate -> candidate.sourceTypes().stream().anyMatch(type -> type.name().equals(source)))
                .sorted(Comparator.comparingInt(candidate -> candidate.sourceRank()))
                .limit(budgets.getOrDefault(source, 3))
                .map(candidate -> {
                    boolean invalidated = invalidated(profileId, candidate.candidateProfileId());
                    String reason = invalidated ? "REALTIME_INVALIDATED" : candidate.exclusionReason();
                    return new CandidateItem(candidate.candidateProfileId(), source, candidate.sourceScore() == null ? java.math.BigDecimal.ZERO : candidate.sourceScore(), List.of("source:" + source, invalidated ? "realtime_invalidation" : "realtime_clear"), candidate.excluded() || invalidated, reason);
                })
                .toList();
            SourceCallResult result = new SourceCallResult(source, timeout ? 200 : 20, candidates.size(), timeout, timeout, timeout, fallback, timeout ? "source timeout simulated for test/demo" : null, candidates);
            repository.insertSourceCallResult(requestId, result);
            results.add(result);
        }
        return new SourceRoutingResult(planId, results, Map.of("baseBudgets", baseBudgets, "adaptiveBudgets", adaptiveBudgets, "budgets", budgets, "healthAware", true, "qualityAware", true, "streamingBackpressureAware", streamingSourceHealthService.getIfAvailable() != null, "liveFeedbackAware", adaptive != null));
    }

    private boolean invalidated(UUID profileId, UUID candidateId) {
        CandidateInvalidationService service = invalidationService.getIfAvailable();
        return service != null && service.invalidated(profileId, candidateId);
    }

    private int sourcePriority(String source) {
        return switch (source) {
            case "GRAPH_MUTUALS" -> 10;
            case "GRAPH_TWO_HOP" -> 9;
            case "VECTOR_SIMILARITY" -> 8;
            case "LOCATION_NEARBY" -> 7;
            default -> 5;
        };
    }

    private java.util.Optional<CandidateSourceType> candidateSourceType(String source) {
        try {
            return java.util.Optional.of(CandidateSourceType.valueOf(source));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private Map<String, Integer> applyStreamingHealth(Map<String, Integer> budgets) {
        com.matchgraph.api.streaming.SourceHealthService service = streamingSourceHealthService.getIfAvailable();
        if (service == null) {
            return budgets;
        }
        Map<String, Integer> adjusted = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : budgets.entrySet()) {
            adjusted.put(entry.getKey(), service.budgetFor(entry.getKey(), entry.getValue()));
        }
        return adjusted;
    }
}
