package com.matchgraph.api.serving;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.realtime.CandidateInvalidationService;
import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.serving.ServingModels.PreRankRun;
import com.matchgraph.api.serving.ServingModels.SourceCallResult;
import com.matchgraph.api.serving.ServingModels.SessionIntentState;
import com.matchgraph.api.streaming.CandidateTrendService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class PreRankService {

    private final FeedFatigueService fatigueService;
    private final PreRankRepository repository;
    private final ObjectProvider<CandidateInvalidationService> invalidationService;
    private final ObjectProvider<CandidateTrendService> trendService;

    public PreRankService(FeedFatigueService fatigueService, PreRankRepository repository, ObjectProvider<CandidateInvalidationService> invalidationService, ObjectProvider<CandidateTrendService> trendService) {
        this.fatigueService = fatigueService;
        this.repository = repository;
        this.invalidationService = invalidationService;
        this.trendService = trendService;
    }

    public PreRankRun preRank(UUID requestId, UUID profileId, List<SourceCallResult> sourceResults, SessionIntentState intent, int limit) {
        Map<UUID, CandidateItem> deduped = new LinkedHashMap<>();
        for (SourceCallResult result : sourceResults) {
            for (CandidateItem candidate : result.candidates()) {
                if (!deduped.containsKey(candidate.candidateProfileId())) {
                    boolean fatigue = fatigueService.suppressed(profileId, candidate.candidateProfileId(), candidate.sourceKey());
                    boolean invalidated = invalidated(profileId, candidate.candidateProfileId());
                    BigDecimal intentWeight = intent == null ? BigDecimal.ZERO : intent.sourceWeights().getOrDefault(candidate.sourceKey(), BigDecimal.ZERO);
                    BigDecimal trendBoost = candidate.hardExcluded() || invalidated ? BigDecimal.ZERO : trendBoost(candidate.candidateProfileId());
                    BigDecimal score = candidate.score().add(BigDecimal.valueOf(sourcePriority(candidate.sourceKey()))).add(intentWeight).add(trendBoost);
                    String filteredReason = invalidated ? "REALTIME_INVALIDATED" : fatigue ? "FATIGUE_SUPPRESSED" : candidate.filteredReason();
                    deduped.put(candidate.candidateProfileId(), new CandidateItem(candidate.candidateProfileId(), candidate.sourceKey(), score, List.of("cheap_source_priority", "session_intent_weight=" + intentWeight, "bounded_trend_boost=" + trendBoost, fatigue ? "fatigue_suppressed" : "fatigue_clear", invalidated ? "realtime_invalidated" : "realtime_clear"), candidate.hardExcluded() || invalidated, filteredReason));
                }
            }
        }
        List<CandidateItem> filtered = deduped.values().stream()
            .filter(candidate -> candidate.hardExcluded() || candidate.filteredReason() != null)
            .toList();
        List<CandidateItem> survivors = deduped.values().stream()
            .filter(candidate -> !candidate.hardExcluded())
            .filter(candidate -> candidate.filteredReason() == null)
            .sorted(Comparator.comparing(CandidateItem::score).reversed().thenComparing(candidate -> candidate.candidateProfileId().toString()))
            .limit(limit)
            .toList();
        Map<String, Object> summary = Map.of("deduped", deduped.size(), "hardExclusionsRemoved", filtered.stream().filter(CandidateItem::hardExcluded).count(), "sessionIntentApplied", intent != null);
        UUID runId = repository.createRun(requestId, deduped.size(), survivors.size(), limit, summary);
        survivors.forEach(item -> repository.insertItem(runId, item, true));
        filtered.forEach(item -> repository.insertItem(runId, item, false));
        return new PreRankRun(runId, survivors, filtered, summary);
    }

    private boolean invalidated(UUID profileId, UUID candidateId) {
        CandidateInvalidationService service = invalidationService.getIfAvailable();
        return service != null && service.invalidated(profileId, candidateId);
    }

    private BigDecimal trendBoost(UUID candidateId) {
        CandidateTrendService service = trendService.getIfAvailable();
        return service == null ? BigDecimal.ZERO : service.safeBoost(candidateId);
    }

    private int sourcePriority(String source) {
        return switch (source) {
            case "GRAPH_MUTUALS" -> 8;
            case "GRAPH_TWO_HOP" -> 7;
            case "VECTOR_SIMILARITY" -> 6;
            case "LOCATION_NEARBY" -> 5;
            case "RECONNECT" -> 4;
            case "COLD_START" -> 3;
            default -> 2;
        };
    }
}
