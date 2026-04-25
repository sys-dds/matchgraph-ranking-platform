package com.matchgraph.api.evaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OfflineEvaluationService {

    private static final Set<String> POSITIVE = Set.of("LIKE", "PROFILE_VIEW", "MATCH_CREATED");
    private static final Set<String> NEGATIVE = Set.of("PASS", "SKIP", "BLOCK", "REPORT");

    private final OfflineEvaluationRepository offlineEvaluationRepository;

    public OfflineEvaluationService(OfflineEvaluationRepository offlineEvaluationRepository) {
        this.offlineEvaluationRepository = offlineEvaluationRepository;
    }

    @Transactional
    public OfflineEvaluationResponse evaluate(OfflineEvaluationRequest request) {
        OfflineEvaluationRequest normalized = request == null ? new OfflineEvaluationRequest(null, null, null, null, null) : request;
        int k = normalized.k() == null ? 10 : normalized.k();
        if (k < 1 || k > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "k must be between 1 and 100");
        }
        OfflineEvaluationRun run = offlineEvaluationRepository.createRun(UUID.randomUUID(), normalized, k);
        OfflineEvaluationResult result = offlineEvaluationRepository.insertResult(run.id(), stats(normalized, k));
        offlineEvaluationRepository.completeRun(run.id());
        return get(run.id());
    }

    public OfflineEvaluationResponse get(UUID runId) {
        OfflineEvaluationRun run = offlineEvaluationRepository.run(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "offline evaluation run not found"));
        OfflineEvaluationResult result = offlineEvaluationRepository.result(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "offline evaluation result not found"));
        return new OfflineEvaluationResponse(run, result);
    }

    private OfflineEvaluationRepository.EvaluationStats stats(OfflineEvaluationRequest request, int k) {
        List<OfflineEvaluationRepository.DecisionLabelRow> rows = offlineEvaluationRepository.decisionRows(request, k);
        Map<UUID, List<OfflineEvaluationRepository.DecisionLabelRow>> byDecision = rows.stream()
            .collect(Collectors.groupingBy(
                OfflineEvaluationRepository.DecisionLabelRow::decisionLogId,
                java.util.LinkedHashMap::new,
                Collectors.toList()
            ));
        int evaluated = byDecision.size();
        int labelled = 0;
        int staleEmbeddingCount = 0;
        BigDecimal precisionSum = BigDecimal.ZERO;
        BigDecimal recallSum = BigDecimal.ZERO;
        BigDecimal mrrSum = BigDecimal.ZERO;
        BigDecimal ndcgSum = BigDecimal.ZERO;
        BigDecimal negativePenaltySum = BigDecimal.ZERO;
        Set<UUID> coveredCandidates = new HashSet<>();
        Set<String> diversitySources = new HashSet<>();

        for (List<OfflineEvaluationRepository.DecisionLabelRow> decisionRows : byDecision.values()) {
            long positives = decisionRows.stream().filter(row -> POSITIVE.contains(row.eventType())).count();
            long negatives = decisionRows.stream().filter(row -> NEGATIVE.contains(row.eventType())).count();
            long labels = positives + negatives;
            if (labels > 0) {
                labelled++;
            }
            precisionSum = precisionSum.add(ratio(positives, Math.min(k, decisionRows.size())));
            recallSum = recallSum.add(labels == 0 ? BigDecimal.ZERO : ratio(positives, labels));
            mrrSum = mrrSum.add(reciprocalRank(decisionRows));
            ndcgSum = ndcgSum.add(ndcg(decisionRows));
            negativePenaltySum = negativePenaltySum.add(ratio(negatives, Math.min(k, decisionRows.size())));
            decisionRows.forEach(row -> {
                coveredCandidates.add(row.candidateProfileId());
                diversitySources.add(row.sourceTypesJson());
            });
            staleEmbeddingCount += (int) decisionRows.stream().filter(row -> "STALE".equals(row.featureFreshnessStatus())).count();
        }

        BigDecimal denominator = BigDecimal.valueOf(Math.max(1, evaluated));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("positiveLabels", POSITIVE);
        result.put("negativeLabels", NEGATIVE);
        result.put("k", k);
        return new OfflineEvaluationRepository.EvaluationStats(
            precisionSum.divide(denominator, 6, RoundingMode.HALF_UP),
            recallSum.divide(denominator, 6, RoundingMode.HALF_UP),
            mrrSum.divide(denominator, 6, RoundingMode.HALF_UP),
            ndcgSum.divide(denominator, 6, RoundingMode.HALF_UP),
            ratio(coveredCandidates.size(), Math.max(1, rows.size())),
            ratio(diversitySources.size(), Math.max(1, rows.size())),
            negativePenaltySum.divide(denominator, 6, RoundingMode.HALF_UP),
            evaluated,
            labelled,
            evaluated - labelled,
            staleEmbeddingCount,
            new HashMap<>(result)
        );
    }

    private BigDecimal reciprocalRank(List<OfflineEvaluationRepository.DecisionLabelRow> rows) {
        return rows.stream()
            .filter(row -> POSITIVE.contains(row.eventType()))
            .map(row -> BigDecimal.ONE.divide(BigDecimal.valueOf(row.position()), 6, RoundingMode.HALF_UP))
            .findFirst()
            .orElse(BigDecimal.ZERO);
    }

    private BigDecimal ndcg(List<OfflineEvaluationRepository.DecisionLabelRow> rows) {
        double dcg = 0.0d;
        int positives = 0;
        for (OfflineEvaluationRepository.DecisionLabelRow row : rows) {
            if (POSITIVE.contains(row.eventType())) {
                positives++;
                dcg += 1.0d / (Math.log(row.position() + 1) / Math.log(2));
            }
        }
        double ideal = 0.0d;
        for (int rank = 1; rank <= positives; rank++) {
            ideal += 1.0d / (Math.log(rank + 1) / Math.log(2));
        }
        return ideal == 0.0d ? BigDecimal.ZERO : BigDecimal.valueOf(dcg / ideal).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }
}
