package com.matchgraph.api.ltr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.matchgraph.api.training.TrainingExample;

import org.springframework.stereotype.Component;

@Component
public class LocalLinearRankerTrainer {

    public TrainingResult train(List<TrainingExample> examples, List<String> featureNames, double split, long seed) {
        List<TrainingExample> labelled = examples.stream()
            .filter(example -> example.labelPositive() || example.labelNegative())
            .sorted(Comparator.comparing(example -> example.id().toString()))
            .toList();
        List<TrainingRow> train = new ArrayList<>();
        List<TrainingRow> validation = new ArrayList<>();
        for (TrainingExample example : labelled) {
            TrainingRow row = new TrainingRow(example, numericFeatures(example, featureNames));
            if (bucket(example.id().toString(), seed) < split) {
                train.add(row);
            } else {
                validation.add(row);
            }
        }
        if (train.isEmpty() && !validation.isEmpty()) {
            train.add(validation.removeFirst());
        }
        if (validation.isEmpty() && train.size() > 1) {
            validation.add(train.removeLast());
        }

        Map<String, BigDecimal> means = new LinkedHashMap<>();
        Map<String, BigDecimal> stddevs = new LinkedHashMap<>();
        for (String feature : featureNames) {
            BigDecimal mean = train.stream()
                .map(row -> row.features().getOrDefault(feature, BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.max(1, train.size())), 6, RoundingMode.HALF_UP);
            means.put(feature, mean);
            BigDecimal variance = train.stream()
                .map(row -> row.features().getOrDefault(feature, BigDecimal.ZERO).subtract(mean))
                .map(delta -> delta.multiply(delta))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.max(1, train.size())), 6, RoundingMode.HALF_UP);
            BigDecimal stddev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).setScale(6, RoundingMode.HALF_UP);
            stddevs.put(feature, stddev);
        }

        Map<String, BigDecimal> rawWeights = new LinkedHashMap<>();
        BigDecimal maxAbs = BigDecimal.ZERO;
        for (String feature : featureNames) {
            BigDecimal positiveMean = mean(train, feature, true);
            BigDecimal negativeMean = mean(train, feature, false);
            BigDecimal raw = positiveMean.subtract(negativeMean).setScale(6, RoundingMode.HALF_UP);
            rawWeights.put(feature, raw);
            maxAbs = maxAbs.max(raw.abs());
        }
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (String feature : featureNames) {
            BigDecimal raw = rawWeights.get(feature);
            weights.put(feature, maxAbs.signum() == 0 ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP) : raw.divide(maxAbs, 6, RoundingMode.HALF_UP));
        }

        List<ScoredRow> scoredValidation = validation.stream()
            .map(row -> new ScoredRow(row, score(row.features(), weights, means, stddevs)))
            .sorted(Comparator.comparing(ScoredRow::score).reversed())
            .toList();
        int k = Math.min(10, scoredValidation.size());
        long positiveAtK = scoredValidation.stream().limit(k).filter(row -> row.row().example().labelPositive()).count();
        BigDecimal precisionAtK = k == 0
            ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP)
            : BigDecimal.valueOf(positiveAtK).divide(BigDecimal.valueOf(k), 6, RoundingMode.HALF_UP);
        BigDecimal averageReward = scoredValidation.stream()
            .map(row -> row.row().example().labelValue())
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(Math.max(1, scoredValidation.size())), 6, RoundingMode.HALF_UP);
        long covered = train.stream().flatMap(row -> row.features().keySet().stream()).distinct().count();
        BigDecimal featureCoverage = BigDecimal.valueOf(covered).divide(BigDecimal.valueOf(Math.max(1, featureNames.size())), 6, RoundingMode.HALF_UP);
        int positiveCount = (int) labelled.stream().filter(TrainingExample::labelPositive).count();
        int negativeCount = (int) labelled.stream().filter(TrainingExample::labelNegative).count();
        Map<String, Object> normalization = new LinkedHashMap<>();
        normalization.put("mean", means);
        normalization.put("stddev", stddevs);
        normalization.put("normalizationSemantics", "z-score over deterministic training split; zero stddev maps normalized value to 0");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("validationPrecisionAtKApprox", precisionAtK);
        metrics.put("validationAverageReward", averageReward);
        metrics.put("featureCoverage", featureCoverage);
        metrics.put("algorithm", "LOCAL_LINEAR_WEIGHTED");
        return new TrainingResult(
            weights,
            normalization,
            metrics,
            train,
            scoredValidation,
            train.size(),
            validation.size(),
            positiveCount,
            negativeCount,
            precisionAtK,
            averageReward,
            featureCoverage
        );
    }

    public BigDecimal score(Map<String, BigDecimal> features, Map<String, BigDecimal> weights, Map<String, BigDecimal> means, Map<String, BigDecimal> stddevs) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : weights.entrySet()) {
            BigDecimal raw = features.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            BigDecimal stddev = stddevs.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            BigDecimal normalized = stddev.signum() == 0
                ? BigDecimal.ZERO
                : raw.subtract(means.getOrDefault(entry.getKey(), BigDecimal.ZERO)).divide(stddev, 6, RoundingMode.HALF_UP);
            total = total.add(normalized.multiply(entry.getValue()));
        }
        return total.setScale(6, RoundingMode.HALF_UP);
    }

    private Map<String, BigDecimal> numericFeatures(TrainingExample example, List<String> featureNames) {
        Map<String, BigDecimal> features = new LinkedHashMap<>();
        for (String feature : featureNames) {
            Object value = example.offlineFeatures().get(feature);
            BigDecimal numeric = numeric(value);
            if (numeric != null) {
                features.put(feature, numeric);
            }
        }
        return features;
    }

    private BigDecimal mean(List<TrainingRow> rows, String feature, boolean positive) {
        List<TrainingRow> matching = rows.stream()
            .filter(row -> row.example().labelPositive() == positive)
            .toList();
        if (matching.isEmpty()) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        return matching.stream()
            .map(row -> row.features().getOrDefault(feature, BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(matching.size()), 6, RoundingMode.HALF_UP);
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

    private double bucket(String key, long seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((key + ":" + seed).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (bytes[i] & 0xff);
            }
            return Long.remainderUnsigned(value, 10_000) / 10_000d;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record TrainingRow(TrainingExample example, Map<String, BigDecimal> features) {
    }

    public record ScoredRow(TrainingRow row, BigDecimal score) {
    }

    public record TrainingResult(
        Map<String, BigDecimal> weights,
        Map<String, Object> normalization,
        Map<String, Object> metrics,
        List<TrainingRow> trainingRows,
        List<ScoredRow> validationRows,
        int trainingExampleCount,
        int validationExampleCount,
        int positiveCount,
        int negativeCount,
        BigDecimal validationPrecisionAtK,
        BigDecimal validationAverageReward,
        BigDecimal featureCoverage
    ) {
    }
}
