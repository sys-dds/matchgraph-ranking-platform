package com.matchgraph.api.serving;

import org.springframework.stereotype.Service;

@Service
public class HeavyRankBudgetService {

    public boolean shouldFallback(String rankingVersion, boolean simulateModelUnavailable, boolean simulateTimeout) {
        return rankingVersion != null && rankingVersion.startsWith("ltr:") && (simulateModelUnavailable || simulateTimeout);
    }
}
