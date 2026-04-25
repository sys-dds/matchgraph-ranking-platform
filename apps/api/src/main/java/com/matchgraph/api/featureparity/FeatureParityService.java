package com.matchgraph.api.featureparity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.matchgraph.api.training.TrainingDatasetRepository;
import com.matchgraph.api.training.TrainingDatasetService;
import com.matchgraph.api.training.TrainingExample;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FeatureParityService {

    private static final BigDecimal DEFAULT_TOLERANCE = BigDecimal.valueOf(0.000001);

    private final FeatureParityRepository repository;
    private final TrainingDatasetService trainingDatasetService;
    private final TrainingDatasetRepository trainingDatasetRepository;

    public FeatureParityService(
        FeatureParityRepository repository,
        TrainingDatasetService trainingDatasetService,
        TrainingDatasetRepository trainingDatasetRepository
    ) {
        this.repository = repository;
        this.trainingDatasetService = trainingDatasetService;
        this.trainingDatasetRepository = trainingDatasetRepository;
    }

    @Transactional
    public FeatureParityRun check(FeatureParityCheckRequest request) {
        if (request == null || (request.datasetRunId() == null && request.decisionLogId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetRunId or decisionLogId is required");
        }
        Map<String, Object> toleranceConfig = request.toleranceConfig() == null ? Map.of() : request.toleranceConfig();
        BigDecimal tolerance = tolerance(toleranceConfig);
        UUID runId = repository.createRun(request, toleranceConfig);
        List<TrainingExample> examples = loadExamples(request);
        int max = request.maxExamples() == null ? examples.size() : Math.min(examples.size(), Math.max(1, request.maxExamples()));
        int matched = 0;
        int skewed = 0;
        int missingOnline = 0;
        int missingOffline = 0;
        int notComparable = 0;
        Map<String, Integer> skewByFeature = new LinkedHashMap<>();
        Set<String> worstSkewed = new LinkedHashSet<>();

        for (TrainingExample example : examples.stream().limit(max).toList()) {
            Set<String> featureNames = featureNames(example, request.featureNames());
            for (String featureName : featureNames) {
                Object online = example.servingFeatures().get(featureName);
                Object offline = example.offlineFeatures().get(featureName);
                Comparison comparison = compare(online, offline, tolerance);
                repository.insertResult(
                    runId,
                    example.id(),
                    featureName,
                    online,
                    offline,
                    comparison.numericDelta(),
                    comparison.status(),
                    comparison.detail()
                );
                switch (comparison.status()) {
                    case "MATCH" -> matched++;
                    case "SKEWED" -> {
                        skewed++;
                        skewByFeature.put(featureName, skewByFeature.getOrDefault(featureName, 0) + 1);
                        worstSkewed.add(featureName);
                    }
                    case "MISSING_ONLINE" -> missingOnline++;
                    case "MISSING_OFFLINE" -> missingOffline++;
                    case "NOT_COMPARABLE" -> notComparable++;
                    default -> throw new IllegalStateException("unknown parity status " + comparison.status());
                }
            }
        }

        int compared = matched + skewed + missingOnline + missingOffline + notComparable;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("comparedCount", compared);
        summary.put("matchedCount", matched);
        summary.put("skewedCount", skewed);
        summary.put("missingOnlineCount", missingOnline);
        summary.put("missingOfflineCount", missingOffline);
        summary.put("notComparableCount", notComparable);
        summary.put("worstSkewedFeatures", worstSkewed.stream().limit(10).toList());
        summary.put("skewByFeatureName", skewByFeature);
        summary.put("onlineSource", "training_examples.serving_features_json from immutable feature snapshots");
        summary.put("offlineSource", "training_examples.offline_features_json");
        repository.completeRun(
            runId,
            new FeatureParityRepository.Summary(compared, matched, skewed, missingOnline, missingOffline, notComparable, summary)
        );
        return get(runId);
    }

    public FeatureParityRun get(UUID runId) {
        return repository.findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "feature parity run not found"));
    }

    private List<TrainingExample> loadExamples(FeatureParityCheckRequest request) {
        if (request.datasetRunId() != null) {
            trainingDatasetService.get(request.datasetRunId());
            return trainingDatasetRepository.examples(request.datasetRunId());
        }
        return trainingDatasetRepository.examplesForDecision(request.decisionLogId());
    }

    private Set<String> featureNames(TrainingExample example, List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            return new LinkedHashSet<>(requested);
        }
        Set<String> names = new LinkedHashSet<>(example.servingFeatures().keySet());
        names.addAll(example.offlineFeatures().keySet());
        names.remove("_offlineReconstructionSource");
        return names;
    }

    private Comparison compare(Object online, Object offline, BigDecimal tolerance) {
        if (online == null && offline == null) {
            return new Comparison("NOT_COMPARABLE", null, Map.of("reason", "both values missing"));
        }
        if (online == null) {
            return new Comparison("MISSING_ONLINE", null, Map.of("reason", "online serving snapshot value missing"));
        }
        if (offline == null) {
            return new Comparison("MISSING_OFFLINE", null, Map.of("reason", "offline training value missing"));
        }
        BigDecimal onlineNumeric = numeric(online);
        BigDecimal offlineNumeric = numeric(offline);
        if (onlineNumeric != null && offlineNumeric != null) {
            BigDecimal delta = onlineNumeric.subtract(offlineNumeric).abs().setScale(6, RoundingMode.HALF_UP);
            return new Comparison(
                delta.compareTo(tolerance) <= 0 ? "MATCH" : "SKEWED",
                delta,
                Map.of("tolerance", tolerance, "comparisonType", "NUMERIC_ABSOLUTE_DELTA")
            );
        }
        if (online instanceof Boolean || offline instanceof Boolean || online instanceof String || offline instanceof String) {
            return new Comparison(
                Objects.equals(String.valueOf(online), String.valueOf(offline)) ? "MATCH" : "SKEWED",
                null,
                Map.of("comparisonType", "BOOLEAN_OR_STRING_EXACT")
            );
        }
        return new Comparison("NOT_COMPARABLE", null, Map.of("reason", "JSON or array feature comparison is not supported"));
    }

    private BigDecimal numeric(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(String.valueOf(number));
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private BigDecimal tolerance(Map<String, Object> config) {
        Object raw = config.get("numericTolerance");
        return raw == null ? DEFAULT_TOLERANCE : new BigDecimal(String.valueOf(raw));
    }

    private record Comparison(String status, BigDecimal numericDelta, Map<String, Object> detail) {
    }
}
