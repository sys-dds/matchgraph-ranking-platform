package com.matchgraph.api.shadow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChampionChallengerService {

    private final ChampionChallengerRepository championChallengerRepository;
    private final ShadowRankingService shadowRankingService;

    public ChampionChallengerService(
        ChampionChallengerRepository championChallengerRepository,
        ShadowRankingService shadowRankingService
    ) {
        this.championChallengerRepository = championChallengerRepository;
        this.shadowRankingService = shadowRankingService;
    }

    @Transactional
    public ChampionChallengerConfig create(ChampionChallengerConfigRequest request) {
        validate(request);
        UUID id = championChallengerRepository.createConfig(request);
        return championChallengerRepository.findConfig(request.configKey().trim())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "champion/challenger config was not persisted"));
    }

    public ChampionChallengerConfig get(String configKey) {
        return championChallengerRepository.findConfig(configKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "champion/challenger config not found"));
    }

    @Transactional
    public ChampionChallengerDecision evaluate(String configKey, ChampionChallengerEvaluateRequest request) {
        ChampionChallengerConfig config = get(configKey);
        if (!"ACTIVE".equals(config.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "champion/challenger config must be ACTIVE");
        }
        if (request == null || request.baselineDecisionLogId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baselineDecisionLogId is required");
        }
        ShadowRankingRun shadowRun = shadowRankingService.run(new ShadowRankingRunRequest(
            request.baselineDecisionLogId(),
            config.challengerRankingVersion(),
            request.limit()
        ));
        int improved = (int) shadowRun.items().stream()
            .filter(item -> item.positionDelta() != null && item.positionDelta() > 0)
            .count();
        int degraded = (int) shadowRun.items().stream()
            .filter(item -> item.positionDelta() != null && item.positionDelta() < 0)
            .count();
        BigDecimal averageDelta = averageDelta(shadowRun);
        BigDecimal topKOverlap = decimalSummary(shadowRun, "topKOverlap");
        int safetyRegressions = safetyRegressionCount(shadowRun);
        boolean guardrailPass = safetyRegressions == 0 && degraded <= Math.max(1, improved + 1);
        String recommendation = recommendation(guardrailPass, improved, degraded, topKOverlap);
        Map<String, Object> summary = Map.of(
            "shadowRunId", shadowRun.id().toString(),
            "guardrailPass", guardrailPass,
            "promotionRecommendation", recommendation,
            "safetyRegressionCount", safetyRegressions,
            "deterministic", true,
            "silentShadowRanking", true
        );
        UUID decisionId = championChallengerRepository.insertDecision(
            config,
            shadowRun,
            improved,
            degraded,
            topKOverlap,
            averageDelta,
            safetyRegressions,
            guardrailPass ? "PASS" : "FAIL",
            recommendation,
            summary
        );
        return championChallengerRepository.findDecision(decisionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "champion/challenger decision was not persisted"));
    }

    private void validate(ChampionChallengerConfigRequest request) {
        if (request == null || request.configKey() == null || request.configKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "configKey is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (request.championRankingVersion() == null || request.championRankingVersion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "championRankingVersion is required");
        }
        if (request.challengerRankingVersion() == null || request.challengerRankingVersion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "challengerRankingVersion is required");
        }
    }

    private BigDecimal averageDelta(ShadowRankingRun shadowRun) {
        long count = shadowRun.items().stream().filter(item -> item.positionDelta() != null).count();
        if (count == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = shadowRun.items().stream()
            .filter(item -> item.positionDelta() != null)
            .map(item -> BigDecimal.valueOf(item.positionDelta()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal decimalSummary(ShadowRankingRun shadowRun, String key) {
        Object value = shadowRun.summary().get(key);
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private int safetyRegressionCount(ShadowRankingRun shadowRun) {
        return (int) shadowRun.items().stream()
            .filter(item -> item.challengerPosition() != null)
            .filter(item -> String.valueOf(item.reasonDelta()).contains("safety_penalty"))
            .filter(item -> item.positionDelta() != null && item.positionDelta() > 0)
            .count();
    }

    private String recommendation(boolean guardrailPass, int improved, int degraded, BigDecimal topKOverlap) {
        if (!guardrailPass) {
            return "REJECT";
        }
        if (improved > degraded && topKOverlap.compareTo(BigDecimal.valueOf(0.50)) >= 0) {
            return "PROMOTE";
        }
        return "HOLD";
    }
}
