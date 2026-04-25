package com.matchgraph.api.serving;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class SlateConstraintService {

    public boolean sourceAllowed(String source, Map<String, Integer> counts, int maxSameSource) {
        return counts.getOrDefault(source, 0) < maxSameSource;
    }

    public Map<String, Integer> emptyCounts() {
        return new HashMap<>();
    }
}
