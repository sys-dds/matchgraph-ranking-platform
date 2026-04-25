package com.matchgraph.api.serving;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.serving.ServingModels.PreRankRun;
import com.matchgraph.api.serving.ServingModels.SourceCallResult;

import org.springframework.stereotype.Service;

@Service
public class PreRankService {

    private final FeedFatigueService fatigueService;

    public PreRankService(FeedFatigueService fatigueService) {
        this.fatigueService = fatigueService;
    }

    public PreRankRun preRank(UUID profileId, List<SourceCallResult> sourceResults, int limit) {
        Map<UUID, CandidateItem> deduped = new LinkedHashMap<>();
        for (SourceCallResult result : sourceResults) {
            for (CandidateItem candidate : result.candidates()) {
                if (!deduped.containsKey(candidate.candidateProfileId())) {
                    boolean fatigue = fatigueService.suppressed(profileId, candidate.candidateProfileId(), candidate.sourceKey());
                    BigDecimal score = candidate.score().add(BigDecimal.valueOf(sourcePriority(candidate.sourceKey())));
                    deduped.put(candidate.candidateProfileId(), new CandidateItem(candidate.candidateProfileId(), candidate.sourceKey(), score, List.of("cheap_source_priority", "session_intent_weight_available"), candidate.hardExcluded(), fatigue ? "FATIGUE_SUPPRESSED" : candidate.filteredReason()));
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
        return new PreRankRun(UUID.randomUUID(), survivors, filtered, Map.of("deduped", deduped.size(), "hardExclusionsRemoved", filtered.stream().filter(CandidateItem::hardExcluded).count()));
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
