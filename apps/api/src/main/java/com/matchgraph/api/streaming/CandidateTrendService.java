package com.matchgraph.api.streaming;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.streaming.StreamingModels.CandidateTrendRun;
import com.matchgraph.api.streaming.StreamingModels.CandidateTrendScore;

import org.springframework.stereotype.Service;

@Service
public class CandidateTrendService {

    private final CandidateTrendRepository repository;

    public CandidateTrendService(CandidateTrendRepository repository) {
        this.repository = repository;
    }

    public CandidateTrendRun detect() {
        List<CandidateTrendScore> scores = repository.candidatesWithWindows().stream()
            .map(this::score)
            .toList();
        return repository.createRun(scores, Map.of(
            "scoredCandidates", scores.size(),
            "boostBound", "0.25",
            "safetyOverride", true
        ));
    }

    public CandidateTrendRun run(UUID runId) {
        return repository.run(runId);
    }

    public CandidateTrendScore latest(UUID candidateId) {
        return repository.latest(candidateId);
    }

    public BigDecimal safeBoost(UUID candidateId) {
        try {
            CandidateTrendScore score = latest(candidateId);
            return score.boostAllowed() ? score.boundedBoost() : BigDecimal.ZERO;
        } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private CandidateTrendScore score(UUID candidateId) {
        Map<String, Object> recent = repository.latestWindow(candidateId, "5m");
        Map<String, Object> baseline = repository.latestWindow(candidateId, "1h");
        BigDecimal recentPositive = number(recent, "likes").add(number(recent, "matches").multiply(BigDecimal.valueOf(2)));
        BigDecimal baselinePositive = number(baseline, "likes").add(number(baseline, "matches").multiply(BigDecimal.valueOf(2)));
        BigDecimal velocity = recentPositive.subtract(baselinePositive.divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP));
        BigDecimal safetyNegative = number(recent, "reports").multiply(BigDecimal.valueOf(3)).add(number(recent, "blocks").multiply(BigDecimal.valueOf(2)));
        BigDecimal hotness = recentPositive.add(velocity.max(BigDecimal.ZERO)).subtract(safetyNegative);
        String direction = safetyNegative.compareTo(BigDecimal.valueOf(2)) >= 0
            ? "SPIKING_NEGATIVE"
            : velocity.compareTo(new BigDecimal("0.5")) > 0 ? "RISING" : velocity.compareTo(new BigDecimal("-0.5")) < 0 ? "FALLING" : "STABLE";
        boolean allowed = safetyNegative.compareTo(BigDecimal.ONE) <= 0;
        BigDecimal boundedBoost = allowed ? hotness.max(BigDecimal.ZERO).min(new BigDecimal("0.25")) : BigDecimal.ZERO;
        return new CandidateTrendScore(
            UUID.randomUUID(),
            candidateId,
            direction,
            velocity,
            hotness,
            safetyNegative,
            boundedBoost,
            allowed,
            allowed ? null : "safety negative signal blocks trend boost",
            Map.of("recentWindow", recent, "baselineWindow", baseline, "bounded", true, "safetyOverridesTrend", true)
        );
    }

    private BigDecimal number(Map<String, Object> map, String key) {
        Object value = map.getOrDefault(key, 0L);
        return value instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : BigDecimal.ZERO;
    }
}
