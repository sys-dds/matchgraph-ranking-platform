package com.matchgraph.api.streaming;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import com.matchgraph.api.streaming.StreamingModels.SourceFeatureWindow;
import com.matchgraph.api.streaming.StreamingModels.SourceHealthSnapshot;

import org.springframework.stereotype.Service;

@Service
public class SourceHealthService {

    private final StreamingFeatureWindowService windowService;
    private final SourceHealthRepository repository;

    public SourceHealthService(StreamingFeatureWindowService windowService, SourceHealthRepository repository) {
        this.windowService = windowService;
        this.repository = repository;
    }

    public SourceHealthSnapshot evaluate(String sourceKey) {
        List<SourceFeatureWindow> windows = windowService.sourceWindows(sourceKey);
        SourceFeatureWindow window = windows.stream().filter(w -> "5m".equals(w.windowKey())).findFirst().orElse(windows.getFirst());
        BigDecimal calls = BigDecimal.valueOf(Math.max(1, window.timeoutCount() + window.fallbackCount() + window.returnedCandidates() + window.emptyResultCount()));
        BigDecimal timeoutRate = BigDecimal.valueOf(window.timeoutCount()).divide(calls, 6, RoundingMode.HALF_UP);
        BigDecimal emptyRate = BigDecimal.valueOf(window.emptyResultCount()).divide(calls, 6, RoundingMode.HALF_UP);
        BigDecimal quality = BigDecimal.ONE.subtract(timeoutRate).subtract(emptyRate.multiply(new BigDecimal("0.5"))).max(BigDecimal.ZERO);
        String status = timeoutRate.compareTo(new BigDecimal("0.50")) > 0 ? "BACKPRESSURED" : quality.compareTo(new BigDecimal("0.50")) < 0 ? "DEGRADED" : "HEALTHY";
        return repository.saveSnapshot(sourceKey, window.latencyMsAvg(), timeoutRate, emptyRate, quality, status, Map.of(
            "approximate", true,
            "window", window.windowKey(),
            "source", "streaming_source_feature_windows"
        ));
    }

    public SourceHealthSnapshot latest(String sourceKey) {
        try {
            return repository.latest(sourceKey);
        } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
            return evaluate(sourceKey);
        }
    }

    public int budgetFor(String sourceKey, int requestedBudget) {
        StreamingModels.SourceBackpressureAction action;
        try {
            action = repository.latestAction(sourceKey);
        } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
            return requestedBudget;
        }
        if ("DISABLE_TEMPORARILY".equals(action.action())) {
            return 0;
        }
        if ("REDUCE_BUDGET".equals(action.action()) || "USE_FALLBACK".equals(action.action())) {
            return Math.max(1, Math.min(requestedBudget, action.budgetAfter()));
        }
        return requestedBudget;
    }
}
