package com.matchgraph.api.modelquality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.training.TrainingDatasetService;
import com.matchgraph.api.training.TrainingExample;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelDriftService {

    private static final BigDecimal EPSILON = BigDecimal.valueOf(0.000001);

    private final ModelQualityRepository repository;
    private final TrainingDatasetService trainingDatasetService;

    public ModelDriftService(ModelQualityRepository repository, TrainingDatasetService trainingDatasetService) {
        this.repository = repository;
        this.trainingDatasetService = trainingDatasetService;
    }

    @Transactional
    public DriftRun detect(DriftRequest request) {
        if (request == null || request.baselineDatasetRunId() == null || request.candidateDatasetRunId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baselineDatasetRunId and candidateDatasetRunId are required");
        }
        UUID runId = repository.createDriftRun(request);
        List<TrainingExample> baseline = trainingDatasetService.examples(request.baselineDatasetRunId());
        List<TrainingExample> candidate = trainingDatasetService.examples(request.candidateDatasetRunId());
        insertDistribution(runId, "label_distribution", "LABEL", labelDistribution(baseline), labelDistribution(candidate));
        insertDistribution(runId, "score_distribution", "SCORE", scoreDistribution(baseline), scoreDistribution(candidate));
        for (String feature : featureNames(baseline, candidate).stream().limit(20).toList()) {
            insertDistribution(runId, "feature:" + feature, "FEATURE", featureDistribution(baseline, feature), featureDistribution(candidate, feature));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("metricSemantics", "PSI-style approximate drift over durable training datasets");
        summary.put("baselineExampleCount", baseline.size());
        summary.put("candidateExampleCount", candidate.size());
        summary.put("segmentKey", request.segmentKey());
        summary.put("approximateMetrics", List.of("psiApprox"));
        repository.completeDrift(runId, summary);
        return get(runId);
    }

    public DriftRun get(UUID runId) {
        return repository.findDrift(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "drift run not found"));
    }

    private void insertDistribution(UUID runId, String key, String type, Map<String, Integer> baseline, Map<String, Integer> candidate) {
        BigDecimal psi = psi(baseline, candidate);
        String status = psi.compareTo(BigDecimal.valueOf(0.25)) > 0 ? "HIGH" : psi.compareTo(BigDecimal.valueOf(0.1)) > 0 ? "MODERATE" : "LOW";
        repository.insertDriftResult(
            runId,
            key,
            type,
            psi,
            null,
            status,
            Map.of(
                "baselineDistribution", baseline,
                "candidateDistribution", candidate,
                "approximation", "PSI-style bucketed estimate with epsilon for zero buckets"
            )
        );
    }

    private Map<String, Integer> labelDistribution(List<TrainingExample> examples) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TrainingExample example : examples) {
            String key = example.labelPositive() ? "positive" : example.labelNegative() ? "negative" : "neutral";
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return counts;
    }

    private Map<String, Integer> scoreDistribution(List<TrainingExample> examples) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TrainingExample example : examples) {
            BigDecimal score = example.labelValue();
            String bucket = score.signum() > 0 ? "positive_reward" : score.signum() < 0 ? "negative_reward" : "zero_reward";
            counts.put(bucket, counts.getOrDefault(bucket, 0) + 1);
        }
        return counts;
    }

    private List<String> featureNames(List<TrainingExample> baseline, List<TrainingExample> candidate) {
        return java.util.stream.Stream.concat(baseline.stream(), candidate.stream())
            .flatMap(example -> example.offlineFeatures().keySet().stream())
            .filter(name -> !name.startsWith("_"))
            .distinct()
            .sorted()
            .toList();
    }

    private Map<String, Integer> featureDistribution(List<TrainingExample> examples, String feature) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TrainingExample example : examples) {
            Object raw = example.offlineFeatures().get(feature);
            BigDecimal value = numeric(raw);
            String bucket;
            if (value == null) {
                bucket = "not_numeric";
            } else if (value.compareTo(BigDecimal.ZERO) < 0) {
                bucket = "lt_0";
            } else if (value.compareTo(BigDecimal.ONE) <= 0) {
                bucket = "0_to_1";
            } else if (value.compareTo(BigDecimal.TEN) <= 0) {
                bucket = "1_to_10";
            } else {
                bucket = "gt_10";
            }
            counts.put(bucket, counts.getOrDefault(bucket, 0) + 1);
        }
        return counts;
    }

    private BigDecimal psi(Map<String, Integer> baseline, Map<String, Integer> candidate) {
        int baselineTotal = baseline.values().stream().mapToInt(Integer::intValue).sum();
        int candidateTotal = candidate.values().stream().mapToInt(Integer::intValue).sum();
        BigDecimal total = BigDecimal.ZERO;
        for (String key : java.util.stream.Stream.concat(baseline.keySet().stream(), candidate.keySet().stream()).distinct().toList()) {
            BigDecimal b = pct(baseline.getOrDefault(key, 0), baselineTotal);
            BigDecimal c = pct(candidate.getOrDefault(key, 0), candidateTotal);
            double component = (c.doubleValue() - b.doubleValue()) * Math.log(c.doubleValue() / b.doubleValue());
            total = total.add(BigDecimal.valueOf(component));
        }
        return total.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(int count, int total) {
        if (total == 0 || count == 0) {
            return EPSILON;
        }
        return BigDecimal.valueOf(count).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal numeric(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(String.valueOf(number));
        }
        try {
            return value == null ? null : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
