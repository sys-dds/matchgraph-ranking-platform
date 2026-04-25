package com.matchgraph.api.causal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.training.TrainingDatasetRepository;
import com.matchgraph.api.training.TrainingExample;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CausalEvaluationService {

    private final TrainingDatasetRepository trainingDatasetRepository;
    private final CausalEvaluationRepository causalEvaluationRepository;

    public CausalEvaluationService(TrainingDatasetRepository trainingDatasetRepository, CausalEvaluationRepository causalEvaluationRepository) {
        this.trainingDatasetRepository = trainingDatasetRepository;
        this.causalEvaluationRepository = causalEvaluationRepository;
    }

    @Transactional
    public CausalEvaluationRun evaluate(CausalEvaluationRequest request) {
        if (request == null || request.datasetRunId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetRunId is required");
        }
        trainingDatasetRepository.findRun(request.datasetRunId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "training dataset run not found"));
        int k = request.k() == null ? 10 : Math.max(1, request.k());
        BigDecimal maxWeight = BigDecimal.valueOf(request.maxWeight() == null ? 20d : Math.max(1d, request.maxWeight()))
            .setScale(6, RoundingMode.HALF_UP);
        UUID runId = causalEvaluationRepository.createRun(new CausalEvaluationRequest(request.datasetRunId(), k, true, maxWeight.doubleValue()), maxWeight);
        List<TrainingExample> examples = trainingDatasetRepository.examples(request.datasetRunId());
        Metrics metrics = compute(examples, k, maxWeight);
        CausalEvaluationResult result = new CausalEvaluationResult(
            null,
            runId,
            scale(metrics.ipsPrecisionAtK),
            scale(metrics.ipsNdcgAtK),
            scale(metrics.weightedAverageReward),
            scale(metrics.effectiveSampleSize),
            scale(metrics.propensityCoverage),
            metrics.excludedDueToMissingPropensity,
            metrics.excludedDueToMissingPropensity > 0,
            metrics.highVarianceWarning,
            metrics.detail,
            null
        );
        causalEvaluationRepository.insertResult(runId, result);
        causalEvaluationRepository.completeRun(runId, Map.of(
            "semantics", "IPS-style estimate from durable training examples and propensity logs; this is not true causal proof.",
            "datasetRunId", request.datasetRunId().toString(),
            "k", k,
            "maxWeight", maxWeight,
            "examples", examples.size()
        ));
        return get(runId);
    }

    public CausalEvaluationRun get(UUID runId) {
        return causalEvaluationRepository.findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "causal evaluation run not found"));
    }

    private Metrics compute(List<TrainingExample> examples, int k, BigDecimal maxWeight) {
        List<Row> included = new ArrayList<>();
        int missing = 0;
        int clipped = 0;
        for (TrainingExample example : examples.stream().limit(k).toList()) {
            PropensityLog log = causalEvaluationRepository.findPropensity(example.id()).orElse(null);
            BigDecimal propensity = log == null ? example.propensity() : log.propensity();
            String source = log == null ? example.propensitySource() : log.propensitySource();
            if (propensity == null || propensity.signum() <= 0 || "UNKNOWN".equals(source)) {
                missing++;
                continue;
            }
            BigDecimal rawWeight = BigDecimal.ONE.divide(propensity, 12, RoundingMode.HALF_UP);
            BigDecimal weight = rawWeight.min(maxWeight);
            if (rawWeight.compareTo(maxWeight) > 0) {
                clipped++;
            }
            included.add(new Row(example, weight));
        }
        BigDecimal sumWeight = sum(included.stream().map(Row::weight).toList());
        BigDecimal sumWeightSquared = sum(included.stream().map(row -> row.weight().multiply(row.weight())).toList());
        BigDecimal reward = sum(included.stream().map(row -> row.weight().multiply(row.example().labelValue())).toList());
        BigDecimal positives = sum(included.stream()
            .filter(row -> row.example().labelPositive())
            .map(Row::weight)
            .toList());
        BigDecimal dcg = BigDecimal.ZERO;
        BigDecimal idealDcg = BigDecimal.ZERO;
        for (int index = 0; index < included.size(); index++) {
            BigDecimal gain = included.get(index).example().labelPositive() ? included.get(index).weight() : BigDecimal.ZERO;
            BigDecimal discount = BigDecimal.valueOf(1d / (Math.log(index + 2d) / Math.log(2d)));
            dcg = dcg.add(gain.multiply(discount));
        }
        List<Row> ideal = included.stream()
            .sorted((left, right) -> Boolean.compare(right.example().labelPositive(), left.example().labelPositive()))
            .toList();
        for (int index = 0; index < ideal.size(); index++) {
            BigDecimal gain = ideal.get(index).example().labelPositive() ? ideal.get(index).weight() : BigDecimal.ZERO;
            BigDecimal discount = BigDecimal.valueOf(1d / (Math.log(index + 2d) / Math.log(2d)));
            idealDcg = idealDcg.add(gain.multiply(discount));
        }
        Metrics metrics = new Metrics();
        metrics.ipsPrecisionAtK = included.isEmpty() ? BigDecimal.ZERO : positives.divide(BigDecimal.valueOf(Math.max(1, included.size())), 12, RoundingMode.HALF_UP);
        metrics.ipsNdcgAtK = idealDcg.signum() == 0 ? BigDecimal.ZERO : dcg.divide(idealDcg, 12, RoundingMode.HALF_UP);
        metrics.weightedAverageReward = sumWeight.signum() == 0 ? BigDecimal.ZERO : reward.divide(sumWeight, 12, RoundingMode.HALF_UP);
        metrics.effectiveSampleSize = sumWeightSquared.signum() == 0 ? BigDecimal.ZERO : sumWeight.multiply(sumWeight).divide(sumWeightSquared, 12, RoundingMode.HALF_UP);
        metrics.propensityCoverage = examples.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(included.size()).divide(BigDecimal.valueOf(Math.min(k, examples.size())), 12, RoundingMode.HALF_UP);
        metrics.excludedDueToMissingPropensity = missing;
        metrics.highVarianceWarning = clipped > Math.max(0, included.size() / 5) || metrics.effectiveSampleSize.compareTo(BigDecimal.valueOf(Math.max(1, included.size()) * 0.35d)) < 0;
        metrics.detail = new LinkedHashMap<>();
        metrics.detail.put("includedIpsRows", included.size());
        metrics.detail.put("excludedDueToMissingPropensity", missing);
        metrics.detail.put("maxWeightClippedRows", clipped);
        metrics.detail.put("semantics", "IPS-style estimate only; missing/UNKNOWN propensity rows excluded.");
        return metrics;
    }

    private BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private record Row(TrainingExample example, BigDecimal weight) {
    }

    private static final class Metrics {
        private BigDecimal ipsPrecisionAtK;
        private BigDecimal ipsNdcgAtK;
        private BigDecimal weightedAverageReward;
        private BigDecimal effectiveSampleSize;
        private BigDecimal propensityCoverage;
        private int excludedDueToMissingPropensity;
        private boolean highVarianceWarning;
        private Map<String, Object> detail;
    }
}
