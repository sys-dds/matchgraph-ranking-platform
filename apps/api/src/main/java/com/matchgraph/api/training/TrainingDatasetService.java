package com.matchgraph.api.training;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TrainingDatasetService {

    private static final int DEFAULT_LABEL_WINDOW_HOURS = 72;

    private final TrainingDatasetRepository repository;
    private final LabelStoreService labelStoreService;

    public TrainingDatasetService(TrainingDatasetRepository repository, LabelStoreService labelStoreService) {
        this.repository = repository;
        this.labelStoreService = labelStoreService;
    }

    @Transactional
    public TrainingDatasetRun create(CreateTrainingDatasetRequest request) {
        if (request == null || request.datasetKey() == null || request.datasetKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetKey is required");
        }
        int labelWindowHours = request.labelWindowHours() == null ? DEFAULT_LABEL_WINDOW_HOURS : request.labelWindowHours();
        if (labelWindowHours < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "labelWindowHours must be positive");
        }
        boolean includeNeutral = request.includeNeutral() == null || request.includeNeutral();
        boolean includeSyntheticAsPrimary = Boolean.TRUE.equals(request.includeSyntheticAsPrimary());
        Map<String, Object> config = new LinkedHashMap<>(request.config() == null ? Map.of() : request.config());
        config.put("includeNeutral", includeNeutral);
        config.put("includeSyntheticAsPrimary", includeSyntheticAsPrimary);
        config.put("evidenceSources", List.of(
            "ranking_decision_logs",
            "ranking_decision_items",
            "feed_snapshots",
            "feed_items",
            "candidate_feature_snapshots",
            "candidate_feature_values",
            "interaction_events",
            "matches",
            "synthetic_ground_truth_labels",
            "candidate_exposure_events"
        ));

        UUID runId = repository.createRun(request, labelWindowHours, config);
        List<TrainingDatasetRepository.SourceExampleFact> facts = repository.sourceExamples(request);
        int exampleCount = 0;
        int labelledCount = 0;
        int positiveCount = 0;
        int negativeCount = 0;
        int neutralCount = 0;
        int missingFeatureCount = 0;
        int staleEmbeddingCount = 0;
        int propensityCount = 0;
        Map<String, Integer> positionDistribution = new LinkedHashMap<>();
        Map<String, Integer> sourceDistribution = new LinkedHashMap<>();
        Map<String, Integer> labelDistribution = new LinkedHashMap<>();

        for (TrainingDatasetRepository.SourceExampleFact fact : facts) {
            TrainingDatasetRepository.FeaturePayload features = repository.featurePayload(fact.featureSnapshotId());
            List<TrainingDatasetRepository.EventFact> events = repository.labelEvents(fact, labelWindowHours);
            TrainingDatasetRepository.SyntheticLabelFact syntheticLabel = repository.syntheticLabel(fact.profileId(), fact.candidateProfileId()).orElse(null);
            LabelStoreService.LabelOutcome label = labelStoreService.resolve(events, syntheticLabel, includeSyntheticAsPrimary, labelWindowHours);
            if (!includeNeutral && label.neutral() && label.components().isEmpty()) {
                continue;
            }
            TrainingDatasetRepository.PropensityFact propensity = repository.propensity(fact);
            UUID exampleId = repository.insertExample(runId, fact, features, label, labelWindowHours, propensity);
            for (LabelStoreService.LabelComponent component : label.components()) {
                repository.insertLabel(exampleId, component, labelWindowHours);
            }
            exampleCount++;
            if (!label.components().isEmpty()) {
                labelledCount++;
            }
            if (label.positive()) {
                positiveCount++;
                increment(labelDistribution, "positive");
            } else if (label.negative()) {
                negativeCount++;
                increment(labelDistribution, "negative");
            } else {
                neutralCount++;
                increment(labelDistribution, "neutral");
            }
            missingFeatureCount += features.missingFeatureCount();
            staleEmbeddingCount += features.staleEmbeddingCount();
            if (propensity.propensity() != null) {
                propensityCount++;
            }
            increment(positionDistribution, positionBucket(fact.position()));
            for (String source : fact.sourceTypes()) {
                increment(sourceDistribution, source);
            }
        }

        BigDecimal propensityCoverage = ratio(propensityCount, exampleCount);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("exampleCount", exampleCount);
        summary.put("labelledCount", labelledCount);
        summary.put("positiveCount", positiveCount);
        summary.put("negativeCount", negativeCount);
        summary.put("neutralCount", neutralCount);
        summary.put("missingFeatureCount", missingFeatureCount);
        summary.put("staleEmbeddingCount", staleEmbeddingCount);
        summary.put("propensityCoverage", propensityCoverage);
        summary.put("syntheticLabelCoverage", syntheticCoverage(runId, exampleCount));
        summary.put("examplesWithoutFeedSnapshotCount", facts.stream().filter(fact -> fact.feedSnapshotId() == null).count());
        summary.put("examplesWithoutDecisionLogCount", facts.stream().filter(fact -> fact.decisionLogId() == null).count());
        summary.put("labelSemantics", "PROFILE_VIEW=+0.25, LIKE=+1, MATCH_CREATED=+2, PASS=-0.25, BLOCK/REPORT=-2; synthetic labels are fallback unless configured primary");

        repository.insertQualityReport(
            runId,
            new TrainingDatasetRepository.QualityCounts(
                exampleCount,
                labelledCount,
                positiveCount,
                negativeCount,
                neutralCount,
                missingFeatureCount,
                staleEmbeddingCount,
                propensityCoverage,
                Map.copyOf(positionDistribution),
                Map.copyOf(sourceDistribution),
                Map.copyOf(labelDistribution),
                summary
            )
        );
        repository.completeRun(runId, summary);
        return get(runId);
    }

    public TrainingDatasetRun get(UUID runId) {
        return repository.findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "training dataset run not found"));
    }

    public List<TrainingExample> examples(UUID runId) {
        get(runId);
        return repository.examples(runId);
    }

    public TrainingDatasetQualityReport quality(UUID runId) {
        get(runId);
        return repository.quality(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "training dataset quality report not found"));
    }

    private long syntheticCoverage(UUID runId, int exampleCount) {
        if (exampleCount == 0) {
            return 0;
        }
        return repository.examples(runId).stream()
            .filter(example -> example.label().containsKey("syntheticCompatibilityLabel"))
            .count();
    }

    private String positionBucket(int position) {
        if (position <= 3) {
            return "1-3";
        }
        if (position <= 10) {
            return "4-10";
        }
        return "11+";
    }

    private void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }
}
