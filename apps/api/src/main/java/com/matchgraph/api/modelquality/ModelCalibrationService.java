package com.matchgraph.api.modelquality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.ltr.LtrModelArtifact;
import com.matchgraph.api.ltr.LtrModelRegistryService;
import com.matchgraph.api.training.TrainingDatasetService;
import com.matchgraph.api.training.TrainingExample;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelCalibrationService {

    private final ModelQualityRepository repository;
    private final TrainingDatasetService trainingDatasetService;
    private final LtrModelRegistryService modelRegistryService;
    private final ModelScoringSupport scoringSupport;

    public ModelCalibrationService(
        ModelQualityRepository repository,
        TrainingDatasetService trainingDatasetService,
        LtrModelRegistryService modelRegistryService,
        ModelScoringSupport scoringSupport
    ) {
        this.repository = repository;
        this.trainingDatasetService = trainingDatasetService;
        this.modelRegistryService = modelRegistryService;
        this.scoringSupport = scoringSupport;
    }

    @Transactional
    public CalibrationRun calibrate(CalibrationRequest request) {
        if (request == null || request.datasetRunId() == null || request.modelKey() == null || request.versionKey() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "modelKey, versionKey, and datasetRunId are required");
        }
        int bucketCount = request.bucketCount() == null ? 10 : Math.max(1, request.bucketCount());
        UUID runId = repository.createCalibrationRun(request, bucketCount);
        LtrModelArtifact artifact = modelRegistryService.getArtifact(request.modelKey(), request.versionKey());
        List<Scored> scored = trainingDatasetService.examples(request.datasetRunId()).stream()
            .map(example -> new Scored(example, scoringSupport.score(example, artifact)))
            .sorted(Comparator.comparing(Scored::score))
            .toList();
        List<BigDecimal> errors = new ArrayList<>();
        for (int i = 0; i < bucketCount; i++) {
            int from = scored.size() * i / bucketCount;
            int to = scored.size() * (i + 1) / bucketCount;
            List<Scored> bucket = scored.subList(from, to);
            BigDecimal predicted = average(bucket.stream().map(Scored::score).toList());
            BigDecimal reward = average(bucket.stream().map(row -> row.example().labelValue()).toList());
            BigDecimal positiveRate = ratio((int) bucket.stream().filter(row -> row.example().labelPositive()).count(), bucket.size());
            BigDecimal error = predicted.subtract(reward).abs().setScale(6, RoundingMode.HALF_UP);
            errors.add(error);
            String status = error.compareTo(BigDecimal.valueOf(0.1)) <= 0 ? "CALIBRATED" : predicted.compareTo(reward) > 0 ? "OVER_CONFIDENT" : "UNDER_CONFIDENT";
            BigDecimal start = bucket.isEmpty() ? BigDecimal.ZERO : bucket.getFirst().score();
            BigDecimal end = bucket.isEmpty() ? BigDecimal.ZERO : bucket.getLast().score();
            repository.insertCalibrationBucket(runId, i, start, end, bucket.size(), predicted, reward, positiveRate, error, status);
        }
        BigDecimal ece = average(errors);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("expectedCalibrationErrorApprox", ece);
        summary.put("worstBucket", errors.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
        summary.put("bucketCount", bucketCount);
        summary.put("examplesScored", scored.size());
        summary.put("metricSemantics", "approximate calibration over durable training examples");
        repository.completeCalibration(runId, summary);
        return get(runId);
    }

    public CalibrationRun get(UUID runId) {
        return repository.findCalibration(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "calibration run not found"));
    }

    private BigDecimal average(List<BigDecimal> values) {
        return values.isEmpty()
            ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP)
            : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(int numerator, int denominator) {
        return denominator == 0 ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP) : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private record Scored(TrainingExample example, BigDecimal score) {
    }
}
