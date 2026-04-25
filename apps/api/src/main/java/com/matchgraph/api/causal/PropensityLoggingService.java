package com.matchgraph.api.causal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.matchgraph.api.training.TrainingDatasetRepository;
import com.matchgraph.api.training.TrainingExample;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PropensityLoggingService {

    private final TrainingDatasetRepository trainingDatasetRepository;
    private final CausalEvaluationRepository causalEvaluationRepository;

    public PropensityLoggingService(
        TrainingDatasetRepository trainingDatasetRepository,
        CausalEvaluationRepository causalEvaluationRepository
    ) {
        this.trainingDatasetRepository = trainingDatasetRepository;
        this.causalEvaluationRepository = causalEvaluationRepository;
    }

    @Transactional
    public PropensityBackfillResult backfill(PropensityBackfillRequest request) {
        if (request == null || request.datasetRunId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetRunId is required");
        }
        trainingDatasetRepository.findRun(request.datasetRunId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "training dataset run not found"));
        List<TrainingExample> examples = trainingDatasetRepository.examples(request.datasetRunId());
        int created = 0;
        int unknown = 0;
        Map<String, Integer> bySource = new LinkedHashMap<>();
        for (TrainingExample example : examples) {
            Estimate estimate = estimate(example);
            causalEvaluationRepository.insertPropensityLog(
                example.id(),
                example.decisionLogId(),
                example.feedSnapshotId(),
                example.feedItemId(),
                example.profileId(),
                example.candidateProfileId(),
                estimate.propensity(),
                estimate.source(),
                estimate.detail()
            );
            created++;
            bySource.merge(estimate.source(), 1, Integer::sum);
            if (estimate.propensity() == null) {
                unknown++;
            }
        }
        return new PropensityBackfillResult(
            request.datasetRunId(),
            examples.size(),
            created,
            unknown,
            Map.of(
                "bySource", bySource,
                "semantics", "Approximate propensity backfill for IPS-style evaluation; UNKNOWN rows are excluded from IPS metrics."
            )
        );
    }

    private Estimate estimate(TrainingExample example) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rankingVersion", example.rankingVersion());
        detail.put("existingPropensitySource", example.propensitySource());
        detail.put("sourceTypes", example.sourceTypes());
        if (example.position() != null && example.position() > 0) {
            double value = Math.max(0.01d, Math.min(1d, 1d / Math.sqrt(example.position() + 1d)));
            detail.put("position", example.position());
            detail.put("formula", "1 / sqrt(position + 1), clamped to [0.01, 1.0]");
            return new Estimate(BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP), "POSITION_APPROX", detail);
        }
        if (example.propensity() != null && example.propensity().signum() > 0) {
            detail.put("source", "training_examples.propensity");
            return new Estimate(example.propensity().setScale(6, RoundingMode.HALF_UP), sourceOrLogged(example.propensitySource()), detail);
        }
        if (example.rankingVersion() != null && example.rankingVersion().contains("experiment")) {
            detail.put("approximation", "experiment context present but allocation percentage was not durably available");
            return new Estimate(null, "UNKNOWN", detail);
        }
        if (example.rankingVersion() != null && example.rankingVersion().contains("bandit")) {
            detail.put("approximation", "bandit context present but epsilon/arm probability was not durably available");
            return new Estimate(null, "UNKNOWN", detail);
        }
        detail.put("approximation", "no position, logged propensity, experiment allocation, bandit epsilon, or interleaving attribution was durably available");
        return new Estimate(null, "UNKNOWN", detail);
    }

    private String sourceOrLogged(String source) {
        if (source == null || source.isBlank()) {
            return "LOGGED";
        }
        return switch (source) {
            case "POSITION_APPROX", "EXPERIMENT_APPROX", "BANDIT_APPROX", "INTERLEAVING_APPROX", "UNKNOWN" -> source;
            default -> "LOGGED";
        };
    }

    private record Estimate(BigDecimal propensity, String source, Map<String, Object> detail) {
    }
}
