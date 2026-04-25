package com.matchgraph.api.streaming;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.streaming.StreamingModels.ExperimentGuardrailDecision;
import com.matchgraph.api.streaming.StreamingModels.ExperimentGuardrailRun;
import com.matchgraph.api.streaming.StreamingModels.LiveQualityAnomaly;

import org.springframework.stereotype.Service;

@Service
public class RealtimeExperimentGuardrailService {

    private final ExperimentGuardrailRepository repository;
    private final LiveQualityAnomalyService anomalyService;

    public RealtimeExperimentGuardrailService(ExperimentGuardrailRepository repository, LiveQualityAnomalyService anomalyService) {
        this.repository = repository;
        this.anomalyService = anomalyService;
    }

    public ExperimentGuardrailRun evaluate(String experimentKey) {
        List<LiveQualityAnomaly> anomalies = anomalyService.list();
        List<ExperimentGuardrailDecision> decisions = anomalies.stream()
            .filter(anomaly -> List.of("REPORT_SPIKE", "BLOCK_SPIKE", "SOURCE_QUALITY_COLLAPSE", "LATENCY_SPIKE", "LIKE_RATE_DROP", "SAFETY_REGRESSION", "FALLBACK_SPIKE").contains(anomaly.anomalyType()))
            .map(anomaly -> decision(experimentKey, anomaly))
            .toList();
        if (decisions.isEmpty()) {
            decisions = List.of(new ExperimentGuardrailDecision(UUID.randomUUID(), experimentKey, null, "PASS", "NO_ACTION", Map.of("evidence", "no recent blocking anomaly")));
        }
        return repository.saveRun(decisions, Map.of("experimentKey", experimentKey, "evidenceBased", true, "statusUpdateSupported", false, "fallbackInstructionPersisted", true));
    }

    public List<ExperimentGuardrailDecision> decisions(String experimentKey) {
        return repository.decisions(experimentKey);
    }

    public ExperimentGuardrailRun pauseIfBad(String experimentKey) {
        return evaluate(experimentKey);
    }

    public boolean fallbackToControl(String experimentKey) {
        return repository.fallbackToControl(experimentKey);
    }

    private ExperimentGuardrailDecision decision(String experimentKey, LiveQualityAnomaly anomaly) {
        String action = "CRITICAL".equals(anomaly.severity()) || "HIGH".equals(anomaly.severity())
            ? "PAUSE_EXPERIMENT"
            : "FALLBACK_SPIKE".equals(anomaly.anomalyType()) || "LATENCY_SPIKE".equals(anomaly.anomalyType()) ? "FALLBACK_TO_CONTROL" : "WARN";
        return new ExperimentGuardrailDecision(UUID.randomUUID(), experimentKey, null, "TRIGGERED", action, Map.of(
            "anomalyType", anomaly.anomalyType(),
            "severity", anomaly.severity(),
            "recommendedAction", anomaly.recommendedAction(),
            "honestStatus", "experiment module does not expose mutation API; serving should fallback to control when this decision is active"
        ));
    }
}
