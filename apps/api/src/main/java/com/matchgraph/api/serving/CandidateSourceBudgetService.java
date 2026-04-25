package com.matchgraph.api.serving;

import java.util.LinkedHashMap;
import java.util.Map;

import com.matchgraph.api.serving.ServingModels.SurfaceConfig;

import org.springframework.stereotype.Service;

@Service
public class CandidateSourceBudgetService {

    public Map<String, Integer> budgets(SurfaceConfig surface) {
        Map<String, Integer> budgets = new LinkedHashMap<>();
        int perSource = Math.max(1, surface.resultSize() / Math.max(1, surface.allowedSources().size()) + 2);
        for (String source : surface.allowedSources()) {
            budgets.put(source, perSource);
        }
        return budgets;
    }
}
