package com.matchgraph.api.synthetic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.matchgraph.api.retrieval.HardExclusionService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroundTruthEvaluationService {

    private final SyntheticPopulationRepository repository;
    private final HardExclusionService hardExclusionService;

    public GroundTruthEvaluationService(SyntheticPopulationRepository repository, HardExclusionService hardExclusionService) {
        this.repository = repository;
        this.hardExclusionService = hardExclusionService;
    }

    @Transactional
    public SyntheticEvaluationRun evaluate(SyntheticEvaluationRequest request) {
        if (request == null || request.syntheticPopulationRunId() == null || request.decisionLogId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "syntheticPopulationRunId and decisionLogId are required");
        }
        int k = request.k() == null ? 10 : Math.max(1, request.k());
        SyntheticPopulationRepository.DecisionFact decision = repository.decision(request.decisionLogId());
        List<SyntheticPopulationRepository.DecisionItemFact> items = repository.decisionItems(request.decisionLogId(), k);
        Map<UUID, SyntheticGroundTruthLabel> labels = repository.labels(request.syntheticPopulationRunId(), decision.profileId()).stream()
            .collect(Collectors.toMap(SyntheticGroundTruthLabel::candidateProfileId, label -> label));
        UUID runId = repository.createEvaluationRun(
            new SyntheticEvaluationRequest(request.syntheticPopulationRunId(), request.decisionLogId(), decision.rankingVersion(), k),
            Map.of("groundTruthSource", "synthetic_ground_truth_labels", "k", k)
        );

        int positiveHits = 0;
        BigDecimal dcg = BigDecimal.ZERO;
        BigDecimal idcg = BigDecimal.ZERO;
        BigDecimal reciprocalRank = BigDecimal.ZERO;
        Set<String> clusters = new HashSet<>();
        int longTail = 0;
        int safetyViolations = 0;
        int evaluatedSafetyPairs = 0;
        for (int i = 0; i < items.size(); i++) {
            SyntheticPopulationRepository.DecisionItemFact item = items.get(i);
            evaluatedSafetyPairs++;
            if (hardExclusionService.exclusionReason(decision.profileId(), item.candidateProfileId()).isPresent()) {
                safetyViolations++;
            }
            SyntheticGroundTruthLabel label = labels.get(item.candidateProfileId());
            BigDecimal relevance = label == null ? BigDecimal.ZERO : label.expectedRelevance();
            if (label != null && "POSITIVE".equals(label.compatibilityLabel())) {
                positiveHits++;
                if (reciprocalRank.signum() == 0) {
                    reciprocalRank = BigDecimal.ONE.divide(BigDecimal.valueOf(i + 1), 6, RoundingMode.HALF_UP);
                }
            }
            dcg = dcg.add(discounted(relevance, i + 1));
            if (item.clusterId() != null) {
                clusters.add(item.clusterId());
            }
            if (item.exposureCount() <= 1) {
                longTail++;
            }
        }
        List<BigDecimal> ideal = labels.values().stream()
            .map(SyntheticGroundTruthLabel::expectedRelevance)
            .sorted((a, b) -> b.compareTo(a))
            .limit(k)
            .toList();
        for (int i = 0; i < ideal.size(); i++) {
            idcg = idcg.add(discounted(ideal.get(i), i + 1));
        }
        BigDecimal precision = ratio(positiveHits, Math.max(1, k));
        BigDecimal ndcg = idcg.signum() == 0 ? BigDecimal.ZERO : dcg.divide(idcg, 6, RoundingMode.HALF_UP);
        BigDecimal clusterCoverage = ratio(clusters.size(), Math.max(1, repository.findRun(request.syntheticPopulationRunId()).orElseThrow().clusterCount()));
        BigDecimal longTailCoverage = ratio(longTail, Math.max(1, items.size()));
        Map<String, Object> metrics = Map.of(
            "precisionAtK", precision,
            "ndcgAtK", ndcg,
            "mrr", reciprocalRank,
            "clusterCoverage", clusterCoverage,
            "longTailCoverage", longTailCoverage,
            "safetyViolationCount", safetyViolations,
            "evaluatedSafetyPairs", evaluatedSafetyPairs,
            "safetyViolationEvidenceStatus", safetyViolations == 0 ? "NO_VIOLATIONS_FOUND" : "VIOLATIONS_FOUND",
            "labelSource", "known synthetic compatibility labels"
        );
        repository.insertEvaluationResult(
            runId,
            precision,
            ndcg,
            reciprocalRank,
            clusterCoverage,
            longTailCoverage,
            Map.of("longTailServedCount", longTail),
            safetyViolations,
            metrics
        );
        repository.completeEvaluationRun(runId, metrics);
        return get(runId);
    }

    public SyntheticEvaluationRun get(UUID runId) {
        return repository.findEvaluationRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "synthetic evaluation run not found"));
    }

    private BigDecimal discounted(BigDecimal relevance, int rank) {
        double denominator = Math.log(rank + 1) / Math.log(2);
        return relevance.divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(Math.max(1, denominator)), 6, RoundingMode.HALF_UP);
    }
}
