package com.matchgraph.api.modelquality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import com.matchgraph.api.ltr.LtrModelArtifact;
import com.matchgraph.api.training.TrainingExample;

import org.springframework.stereotype.Component;

@Component
public class ModelScoringSupport {

    public BigDecimal score(TrainingExample example, LtrModelArtifact artifact) {
        Map<String, BigDecimal> weights = numericMap(artifact.weights());
        Map<String, BigDecimal> means = nestedNumericMap(artifact.normalization(), "mean");
        Map<String, BigDecimal> stddevs = nestedNumericMap(artifact.normalization(), "stddev");
        BigDecimal total = BigDecimal.ZERO;
        for (String feature : artifact.featureNames()) {
            BigDecimal weight = weights.getOrDefault(feature, BigDecimal.ZERO);
            BigDecimal raw = numeric(example.offlineFeatures().get(feature));
            if (raw == null) {
                raw = BigDecimal.ZERO;
            }
            BigDecimal stddev = stddevs.getOrDefault(feature, BigDecimal.ZERO);
            BigDecimal normalized = stddev.signum() == 0
                ? BigDecimal.ZERO
                : raw.subtract(means.getOrDefault(feature, BigDecimal.ZERO)).divide(stddev, 6, RoundingMode.HALF_UP);
            total = total.add(normalized.multiply(weight));
        }
        return total.setScale(6, RoundingMode.HALF_UP);
    }

    public Map<String, BigDecimal> contributions(TrainingExample example, LtrModelArtifact artifact) {
        Map<String, BigDecimal> weights = numericMap(artifact.weights());
        Map<String, BigDecimal> means = nestedNumericMap(artifact.normalization(), "mean");
        Map<String, BigDecimal> stddevs = nestedNumericMap(artifact.normalization(), "stddev");
        Map<String, BigDecimal> contributions = new LinkedHashMap<>();
        for (String feature : artifact.featureNames()) {
            BigDecimal raw = numeric(example.offlineFeatures().get(feature));
            BigDecimal stddev = stddevs.getOrDefault(feature, BigDecimal.ZERO);
            BigDecimal normalized = stddev.signum() == 0 || raw == null
                ? BigDecimal.ZERO
                : raw.subtract(means.getOrDefault(feature, BigDecimal.ZERO)).divide(stddev, 6, RoundingMode.HALF_UP);
            contributions.put(feature, normalized.multiply(weights.getOrDefault(feature, BigDecimal.ZERO)).setScale(6, RoundingMode.HALF_UP));
        }
        return contributions;
    }

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> nestedNumericMap(Map<String, Object> root, String key) {
        Object raw = root.get(key);
        if (raw instanceof Map<?, ?> map) {
            Map<String, BigDecimal> values = new LinkedHashMap<>();
            map.forEach((name, value) -> {
                BigDecimal numeric = numeric(value);
                if (numeric != null) {
                    values.put(String.valueOf(name), numeric);
                }
            });
            return values;
        }
        return Map.of();
    }

    private Map<String, BigDecimal> numericMap(Map<String, Object> root) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        root.forEach((key, value) -> {
            BigDecimal numeric = numeric(value);
            if (numeric != null) {
                values.put(key, numeric);
            }
        });
        return values;
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
