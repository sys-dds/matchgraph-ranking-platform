package com.matchgraph.api.rolloutgate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.ltr.LtrModelRegistryRepository;
import com.matchgraph.api.ltr.LtrModelRegistryService;
import com.matchgraph.api.ltr.LtrModelVersion;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelRolloutGateService {

    private static final List<String> CHECK_KEYS = List.of(
        "offline_metric_improvement",
        "counterfactual_risk",
        "calibration",
        "drift",
        "causal_estimate",
        "long_term_reward",
        "safety_regression",
        "exposure_fairness",
        "shadow_guardrail",
        "interleaving_result",
        "feature_parity",
        "training_data_quality"
    );

    private final ModelRolloutGateRepository repository;
    private final ModelAcceptanceReportService acceptanceReportService;
    private final LtrModelRegistryService modelRegistryService;
    private final LtrModelRegistryRepository modelRegistryRepository;

    public ModelRolloutGateService(
        ModelRolloutGateRepository repository,
        ModelAcceptanceReportService acceptanceReportService,
        LtrModelRegistryService modelRegistryService,
        LtrModelRegistryRepository modelRegistryRepository
    ) {
        this.repository = repository;
        this.acceptanceReportService = acceptanceReportService;
        this.modelRegistryService = modelRegistryService;
        this.modelRegistryRepository = modelRegistryRepository;
    }

    @Transactional
    public ModelRolloutGateRun create(ModelRolloutGateRequest request) {
        if (request == null || blank(request.candidateModelKey()) || blank(request.candidateVersionKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "candidateModelKey and candidateVersionKey are required");
        }
        LtrModelVersion version = modelRegistryService.getVersion(request.candidateModelKey(), request.candidateVersionKey());
        UUID runId = repository.createRun(request);
        for (String checkKey : CHECK_KEYS) {
            repository.insertCheck(runId, evidence(checkKey, version));
        }
        ModelRolloutGateRun provisional = repository.findRun(runId).orElseThrow();
        String recommendation = recommend(provisional.checks());
        Map<String, Object> summary = Map.of(
            "recommendationLogic", "REJECT on required FAIL/NOT_AVAILABLE; HOLD on required WARN or severe optional warning; APPROVE only when required checks PASS and optional checks are acceptable.",
            "missingOptionalEvidence", "WARN, not PASS",
            "activeTransition", "not automatic"
        );
        repository.completeRun(runId, recommendation, summary);
        ModelRolloutGateRun completed = get(runId);
        repository.insertReport(runId, request.candidateModelKey(), request.candidateVersionKey(), recommendation, acceptanceReportService.humanReadable(completed));
        return completed;
    }

    public ModelRolloutGateRun get(UUID runId) {
        return repository.findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "model rollout gate run not found"));
    }

    public ModelAcceptanceReport report(String modelKey, String versionKey) {
        return acceptanceReportService.get(modelKey, versionKey);
    }

    @Transactional
    public LtrModelVersion approveIfSafe(String modelKey, String versionKey) {
        ModelRolloutGateRun run = repository.latestRun(modelKey, versionKey)
            .orElseGet(() -> create(new ModelRolloutGateRequest(modelKey, versionKey, null, null, Map.of())));
        if (!"APPROVE".equals(run.recommendation())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "model version is not safe to approve: " + run.recommendation());
        }
        LtrModelVersion version = modelRegistryService.getVersion(modelKey, versionKey);
        if (!"APPROVED".equals(version.status())) {
            modelRegistryRepository.updateVersionStatus(version.id(), "APPROVED");
            modelRegistryRepository.insertTransition(version.id(), version.status(), "APPROVED", "rollout gate approve-if-safe", Map.of("gateRunId", run.id().toString()));
        }
        return modelRegistryService.getVersion(modelKey, versionKey);
    }

    private ModelRolloutGateRepository.GateCheck evidence(String checkKey, LtrModelVersion version) {
        boolean required = required(checkKey);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("modelKey", version.modelKey());
        detail.put("versionKey", version.versionKey());
        detail.put("evidencePolicy", "No evidence is fabricated; missing required evidence blocks approval.");
        return switch (checkKey) {
            case "offline_metric_improvement" -> metricCheck(checkKey, required, version.metrics() != null && !version.metrics().isEmpty(), "metrics_json");
            case "training_data_quality" -> metricCheck(checkKey, required, version.trainingDatasetRunId() != null, "training_dataset_run_id");
            case "feature_parity" -> existsCheck(checkKey, required, "select count(*) from feature_parity_runs where dataset_run_id = ?", version.trainingDatasetRunId());
            case "calibration" -> existsCheck(checkKey, required, "select count(*) from model_calibration_runs where model_key = ? and version_key = ?", version.modelKey(), version.versionKey());
            case "drift" -> existsCheck(checkKey, required, "select count(*) from model_drift_runs where candidate_model_version = ?", version.versionKey());
            case "causal_estimate" -> existsCheck(checkKey, required, "select count(*) from causal_evaluation_runs where dataset_run_id = ?", version.trainingDatasetRunId());
            case "long_term_reward" -> existsCheck(checkKey, required, "select count(*) from long_term_reward_runs where dataset_run_id = ?", version.trainingDatasetRunId());
            case "shadow_guardrail" -> existsCheck(checkKey, required, "select count(*) from shadow_ranking_runs where challenger_ranking_version = ?", "ltr:" + version.modelKey() + ":" + version.versionKey());
            case "counterfactual_risk" -> existsCheck(checkKey, required, "select count(*) from counterfactual_evaluation_runs where candidate_ranking_version = ?", "ltr:" + version.modelKey() + ":" + version.versionKey());
            default -> new ModelRolloutGateRepository.GateCheck(checkKey, required ? "NOT_AVAILABLE" : "WARN", required, null, "durable evidence required", detail);
        };
    }

    private ModelRolloutGateRepository.GateCheck metricCheck(String checkKey, boolean required, boolean present, String evidence) {
        return new ModelRolloutGateRepository.GateCheck(
            checkKey,
            present ? "PASS" : (required ? "NOT_AVAILABLE" : "WARN"),
            required,
            present ? evidence : null,
            "durable evidence required",
            Map.of("evidence", evidence)
        );
    }

    private ModelRolloutGateRepository.GateCheck existsCheck(String checkKey, boolean required, String sql, Object... args) {
        boolean present = args.length > 0 && args[args.length - 1] != null && repository.exists(sql, args);
        return new ModelRolloutGateRepository.GateCheck(
            checkKey,
            present ? "PASS" : (required ? "NOT_AVAILABLE" : "WARN"),
            required,
            present ? "present" : null,
            "durable evidence required",
            Map.of("queryEvidence", present)
        );
    }

    private boolean required(String checkKey) {
        return List.of("offline_metric_improvement", "counterfactual_risk", "calibration", "drift", "causal_estimate", "long_term_reward", "feature_parity", "training_data_quality").contains(checkKey);
    }

    private String recommend(List<ModelRolloutGateCheck> checks) {
        if (checks.stream().anyMatch(check -> check.required() && ("FAIL".equals(check.status()) || "NOT_AVAILABLE".equals(check.status())))) {
            return "REJECT";
        }
        if (checks.stream().anyMatch(check -> check.required() && "WARN".equals(check.status()))) {
            return "HOLD";
        }
        if (checks.stream().anyMatch(check -> !check.required() && "FAIL".equals(check.status()))) {
            return "HOLD";
        }
        return "APPROVE";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
