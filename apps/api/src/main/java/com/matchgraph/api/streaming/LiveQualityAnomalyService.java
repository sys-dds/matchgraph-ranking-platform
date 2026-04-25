package com.matchgraph.api.streaming;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.streaming.StreamingModels.LiveQualityAnomaly;
import com.matchgraph.api.streaming.StreamingModels.LiveQualityAnomalyRun;

import org.springframework.stereotype.Service;

@Service
public class LiveQualityAnomalyService {

    private final LiveQualityAnomalyRepository repository;

    public LiveQualityAnomalyService(LiveQualityAnomalyRepository repository) {
        this.repository = repository;
    }

    public LiveQualityAnomalyRun detect() {
        List<LiveQualityAnomaly> anomalies = new ArrayList<>();
        for (Map<String, Object> row : repository.latestSourceHealth()) {
            BigDecimal quality = number(row.get("quality_score"));
            BigDecimal timeoutRate = number(row.get("timeout_rate"));
            BigDecimal latency = number(row.get("latency_p95_ms"));
            String source = String.valueOf(row.get("source_key"));
            if (quality.compareTo(new BigDecimal("0.50")) < 0) {
                anomalies.add(anomaly("SOURCE_QUALITY_COLLAPSE", "HIGH", null, source, quality, BigDecimal.ONE, new BigDecimal("0.50"), "BACKPRESSURE_SOURCE", row));
            }
            if (timeoutRate.compareTo(new BigDecimal("0.25")) > 0) {
                anomalies.add(anomaly("FALLBACK_SPIKE", "MEDIUM", null, source, timeoutRate, BigDecimal.ZERO, new BigDecimal("0.25"), "BACKPRESSURE_SOURCE", row));
            }
            if (latency.compareTo(BigDecimal.valueOf(250)) > 0) {
                anomalies.add(anomaly("LATENCY_SPIKE", "MEDIUM", null, source, latency, BigDecimal.valueOf(100), BigDecimal.valueOf(250), "BACKPRESSURE_SOURCE", row));
            }
        }
        return repository.saveRun(anomalies, Map.of(
            "implementedTypes", LiveQualityAnomalyRepository.ANOMALY_TYPES,
            "approximate", true,
            "missingBaselinePolicy", "warning_not_fake_anomaly",
            "detectedCount", anomalies.size()
        ));
    }

    public LiveQualityAnomalyRun run(UUID runId) {
        return repository.run(runId);
    }

    public List<LiveQualityAnomaly> list() {
        return repository.list();
    }

    private LiveQualityAnomaly anomaly(String type, String severity, String surface, String source, BigDecimal observed, BigDecimal baseline, BigDecimal threshold, String action, Map<String, Object> evidence) {
        return new LiveQualityAnomaly(UUID.randomUUID(), type, severity, surface, source, action, observed, baseline, threshold, Map.of("storedEvidence", evidence, "approximate", true));
    }

    private BigDecimal number(Object value) {
        return value instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : BigDecimal.ZERO;
    }
}
