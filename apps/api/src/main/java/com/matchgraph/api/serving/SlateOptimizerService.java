package com.matchgraph.api.serving;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.serving.ServingModels.ServedItem;
import com.matchgraph.api.serving.ServingModels.SlateOptimizationRun;

import org.springframework.stereotype.Service;

@Service
public class SlateOptimizerService {

    private final SlateConstraintService constraintService;

    public SlateOptimizerService(SlateConstraintService constraintService) {
        this.constraintService = constraintService;
    }

    public SlateOptimizationRun optimize(List<CandidateItem> ranked, int resultSize, boolean simulatePartial) {
        Map<String, Integer> sourceCounts = constraintService.emptyCounts();
        List<ServedItem> selected = new ArrayList<>();
        List<CandidateItem> dropped = new ArrayList<>();
        int position = 1;
        for (CandidateItem candidate : ranked) {
            if (candidate.hardExcluded()) {
                dropped.add(new CandidateItem(candidate.candidateProfileId(), candidate.sourceKey(), candidate.score(), candidate.reasons(), true, "HARD_EXCLUDED"));
                continue;
            }
            if (!constraintService.sourceAllowed(candidate.sourceKey(), sourceCounts, 2)) {
                dropped.add(new CandidateItem(candidate.candidateProfileId(), candidate.sourceKey(), candidate.score(), candidate.reasons(), false, "MAX_SAME_SOURCE_TOP_K"));
                continue;
            }
            selected.add(new ServedItem(candidate.candidateProfileId(), position++, candidate.score(), List.of(candidate.sourceKey()), candidate.reasons()));
            sourceCounts.merge(candidate.sourceKey(), 1, Integer::sum);
            if (selected.size() >= resultSize || (simulatePartial && selected.size() >= Math.max(1, resultSize / 2))) {
                break;
            }
        }
        boolean partial = selected.size() < resultSize;
        return new SlateOptimizationRun(UUID.randomUUID(), selected, dropped, partial, partial ? "partial slate: constraints or requested simulation limited output" : null);
    }
}
