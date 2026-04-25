package com.matchgraph.api.ltr;

import java.math.BigDecimal;
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
public class LtrTrainingService {

    private final TrainingDatasetService trainingDatasetService;
    private final LtrModelRegistryService modelRegistryService;
    private final LtrTrainingRepository repository;
    private final LocalLinearRankerTrainer trainer;

    public LtrTrainingService(
        TrainingDatasetService trainingDatasetService,
        LtrModelRegistryService modelRegistryService,
        LtrTrainingRepository repository,
        LocalLinearRankerTrainer trainer
    ) {
        this.trainingDatasetService = trainingDatasetService;
        this.modelRegistryService = modelRegistryService;
        this.repository = repository;
        this.trainer = trainer;
    }

    @Transactional
    public LtrTrainingResponse train(LtrTrainingRequest request) {
        validate(request);
        trainingDatasetService.get(request.datasetRunId());
        LtrModelVersion version = modelRegistryService.getVersion(request.modelKey(), request.versionKey());
        List<TrainingExample> examples = trainingDatasetService.examples(request.datasetRunId());
        List<TrainingExample> labelled = examples.stream()
            .filter(example -> example.labelPositive() || example.labelNegative())
            .toList();
        long positives = labelled.stream().filter(TrainingExample::labelPositive).count();
        long negatives = labelled.stream().filter(TrainingExample::labelNegative).count();
        boolean tinyAllowed = Boolean.TRUE.equals(request.allowTinyDataset());
        if (positives == 0 || negatives == 0 || (!tinyAllowed && labelled.size() < 5)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "insufficient labelled data: requires at least one positive, one negative, and 5 labelled examples unless allowTinyDataset=true"
            );
        }
        List<String> featureNames = request.featureNames() == null || request.featureNames().isEmpty()
            ? inferNumericFeatureNames(labelled)
            : request.featureNames();
        if (featureNames.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one numeric feature is required");
        }
        double split = request.trainValidationSplit() == null ? 0.8d : request.trainValidationSplit();
        if (split <= 0 || split >= 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trainValidationSplit must be between 0 and 1");
        }
        long seed = request.randomSeed() == null ? 0L : request.randomSeed();
        Map<String, Object> config = new LinkedHashMap<>(request.hyperparameters() == null ? Map.of() : request.hyperparameters());
        config.put("trainValidationSplit", split);
        config.put("randomSeed", seed);
        config.put("allowTinyDataset", tinyAllowed);
        UUID runId = repository.createRun(new LtrTrainingRequest(
            request.datasetRunId(),
            request.modelKey(),
            request.versionKey(),
            "LOCAL_LINEAR_WEIGHTED",
            featureNames,
            split,
            seed,
            request.hyperparameters(),
            tinyAllowed
        ), featureNames, config);

        LocalLinearRankerTrainer.TrainingResult result = trainer.train(labelled, featureNames, split, seed);
        for (LocalLinearRankerTrainer.TrainingRow row : result.trainingRows()) {
            repository.insertSnapshot(runId, row.example().id(), "TRAIN", asObjectMap(row.features()), row.example().labelValue(), null);
        }
        for (LocalLinearRankerTrainer.ScoredRow row : result.validationRows()) {
            repository.insertSnapshot(runId, row.row().example().id(), "VALIDATION", asObjectMap(row.row().features()), row.row().example().labelValue(), row.score());
        }
        repository.insertMetrics(runId, result);
        Map<String, Object> artifactMetadata = new LinkedHashMap<>();
        artifactMetadata.put("algorithm", "LOCAL_LINEAR_WEIGHTED");
        artifactMetadata.put("datasetRunId", request.datasetRunId().toString());
        artifactMetadata.put("trainingRunId", runId.toString());
        artifactMetadata.put("reproducibility", "datasetRunId + featureNames + randomSeed + LOCAL_LINEAR_WEIGHTED");
        modelRegistryService.storeArtifact(
            version.id(),
            runId,
            request.datasetRunId(),
            asObjectMap(result.weights()),
            featureNames,
            result.normalization(),
            artifactMetadata,
            result.metrics()
        );
        Map<String, Object> summary = new LinkedHashMap<>(result.metrics());
        summary.put("weightsLearned", result.weights());
        summary.put("normalization", result.normalization());
        repository.completeRun(runId, summary);
        LtrTrainingRun run = get(runId);
        LtrTrainingMetrics metrics = repository.metrics(runId).orElseThrow();
        LtrModelVersion trainedVersion = modelRegistryService.getVersion(request.modelKey(), request.versionKey());
        LtrModelArtifact artifact = modelRegistryService.getArtifact(request.modelKey(), request.versionKey());
        return new LtrTrainingResponse(run, metrics, trainedVersion, artifact);
    }

    public LtrTrainingRun get(UUID trainingRunId) {
        return repository.findRun(trainingRunId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LTR training run not found"));
    }

    public LtrTrainingMetrics metrics(UUID trainingRunId) {
        get(trainingRunId);
        return repository.metrics(trainingRunId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LTR training metrics not found"));
    }

    private void validate(LtrTrainingRequest request) {
        if (request == null || request.datasetRunId() == null || blank(request.modelKey()) || blank(request.versionKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetRunId, modelKey, and versionKey are required");
        }
        String algorithm = request.algorithm() == null ? "LOCAL_LINEAR_WEIGHTED" : request.algorithm();
        if (!"LOCAL_LINEAR_WEIGHTED".equals(algorithm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only LOCAL_LINEAR_WEIGHTED is implemented");
        }
    }

    private List<String> inferNumericFeatureNames(List<TrainingExample> examples) {
        return examples.stream()
            .flatMap(example -> example.offlineFeatures().entrySet().stream())
            .filter(entry -> numeric(entry.getValue()) != null)
            .map(Map.Entry::getKey)
            .distinct()
            .sorted()
            .toList();
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

    private Map<String, Object> asObjectMap(Map<String, BigDecimal> values) {
        return new LinkedHashMap<>(values);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
