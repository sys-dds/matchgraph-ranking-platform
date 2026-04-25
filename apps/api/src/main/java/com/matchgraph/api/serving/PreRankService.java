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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class PreRankService {

    private final FeedFatigueService fatigueService;
    private final PreRankRepository repository;
    private final ObjectProvider<CandidateInvalidationService> invalidationService;

    public PreRankService(FeedFatigueService fatigueService, PreRankRepository repository, ObjectProvider<CandidateInvalidationService> invalidationService) {
        this.fatigueService = fatigueService;
        this.repository = repository;
        this.invalidationService = invalidationService;
    }

    public PreRankRun preRank(UUID requestId, UUID profileId, List<SourceCallResult> sourceResults, SessionIntentState intent, int limit) {
        Map<UUID, CandidateItem> deduped = new LinkedHashMap<>();
        for (SourceCallResult result : sourceResults) {
            for (CandidateItem candidate : result.candidates()) {
                if (!deduped.containsKey(candidate.candidateProfileId())) {
                    boolean fatigue = fatigueService.suppressed(profileId, candidate.candidateProfileId(), candidate.sourceKey());
                    boolean invalidated = invalidated(profileId, candidate.candidateProfileId());
                    BigDecimal intentWeight = intent == null ? BigDecimal.ZERO : intent.sourceWeights().getOrDefault(candidate.sourceKey(), BigDecimal.ZERO);
                    BigDecimal score = candidate.score().add(BigDecimal.valueOf(sourcePriority(candidate.sourceKey()))).add(intentWeight);
                    String filteredReason = invalidated ? "REALTIME_INVALIDATED" : fatigue ? "FATIGUE_SUPPRESSED" : candidate.filteredReason();
                    deduped.put(candidate.candidateProfileId(), new CandidateItem(candidate.candidateProfileId(), candidate.sourceKey(), score, List.of("cheap_source_priority", "session_intent_weight=" + intentWeight, fatigue ? "fatigue_suppressed" : "fatigue_clear", invalidated ? "realtime_invalidated" : "realtime_clear"), candidate.hardExcluded() || invalidated, filteredReason));
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
