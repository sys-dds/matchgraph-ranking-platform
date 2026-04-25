package com.matchgraph.api.serving;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class ServingDegradationService {

    public List<String> warnings(boolean sourceTimeout, boolean modelFallback, boolean partialSlate, int servedCount) {
        List<String> warnings = new ArrayList<>();
        if (sourceTimeout) {
            warnings.add("source timeout/fallback recorded");
        }
        if (modelFallback) {
            warnings.add("model fallback recorded");
        }
        if (partialSlate || servedCount == 0) {
            warnings.add("partial result recorded");
        }
        return warnings;
    }
}
