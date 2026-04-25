package com.matchgraph.api.serving;

import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigDecimal;

import com.matchgraph.api.serving.ServingModels.SurfaceConfig;
import com.matchgraph.api.serving.ServingModels.SessionIntentState;

import org.springframework.stereotype.Service;

@Service
public class CandidateSourceBudgetService {

    public Map<String, Integer> budgets(SurfaceConfig surface) {
        return budgets(surface, null);
    }

    public Map<String, Integer> budgets(SurfaceConfig surface, SessionIntentState intent) {
        Map<String, Integer> budgets = new LinkedHashMap<>();
        int perSource = Math.max(1, surface.resultSize() / Math.max(1, surface.allowedSources().size()) + 2);
        for (String source : surface.allowedSources()) {
            BigDecimal weight = intent == null ? BigDecimal.ZERO : intent.sourceWeights().getOrDefault(source, BigDecimal.ZERO);
            int adjusted = perSource + weight.multiply(BigDecimal.valueOf(2)).intValue();
            budgets.put(source, Math.max(1, Math.min(surface.resultSize(), adjusted)));
        }
        return budgets;
    }
}
