package com.matchgraph.api.streaming;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.streaming.StreamingModels.LiveQualityAnomaly;
import com.matchgraph.api.streaming.StreamingModels.LiveQualityAnomalyRun;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LiveQualityAnomalyRepository {

    public static final List<String> ANOMALY_TYPES = List.of(
        "CTR_DROP",
        "LIKE_RATE_DROP",
        "PASS_RATE_SPIKE",
        "REPORT_SPIKE",
        "BLOCK_SPIKE",
        "SOURCE_QUALITY_COLLAPSE",
        "MODEL_SCORE_DRIFT",
        "LOW_CANDIDATE_COUNT",
        "LATENCY_SPIKE",
        "FALLBACK_SPIKE",
        "SAFETY_REGRESSION"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LiveQualityAnomalyRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public LiveQualityAnomalyRun saveRun(List<LiveQualityAnomaly> anomalies, Map<String, Object> summary) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into live_quality_anomaly_runs (id, status, approximate, summary_json) values (?, 'COMPLETED', true, ?::jsonb)",
            runId,
            json(summary)
        );
        for (LiveQualityAnomaly anomaly : anomalies) {
            jdbcTemplate.update(
                """
                    insert into live_quality_anomalies (
                        id, run_id, anomaly_type, severity, affected_surface, affected_source,
                        observed_value, baseline_value, threshold_value, recommended_action, evidence_json
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                anomaly.id(),
                runId,
                anomaly.anomalyType(),
                anomaly.severity(),
                anomaly.affectedSurface(),
                anomaly.affectedSource(),
                anomaly.observedValue(),
                anomaly.baselineValue(),
                anomaly.thresholdValue(),
                anomaly.recommendedAction(),
                json(anomaly.evidence())
            );
        }
        return new LiveQualityAnomalyRun(runId, "COMPLETED", true, summary, anomalies);
    }

    public LiveQualityAnomalyRun run(UUID runId) {
        Map<String, Object> summary = jdbcTemplate.queryForObject(
            "select summary_json from live_quality_anomaly_runs where id = ?",
            (rs, rowNum) -> readMap(rs.getString("summary_json")),
            runId
        );
        return new LiveQualityAnomalyRun(runId, "COMPLETED", true, summary, list(runId));
    }

    public List<LiveQualityAnomaly> list() {
        return jdbcTemplate.query(
            """
                select id, anomaly_type, severity, affected_surface, affected_source, observed_value,
                       baseline_value, threshold_value, recommended_action, evidence_json
                from live_quality_anomalies order by created_at desc
                """,
            this::anomaly
        );
    }

    public List<LiveQualityAnomaly> list(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, anomaly_type, severity, affected_surface, affected_source, observed_value,
                       baseline_value, threshold_value, recommended_action, evidence_json
                from live_quality_anomalies where run_id = ? order by created_at desc
                """,
            this::anomaly,
            runId
        );
    }

    public List<Map<String, Object>> latestSourceHealth() {
        return jdbcTemplate.queryForList(
            """
                select distinct on (source_key) source_key, health_status, quality_score, timeout_rate, empty_result_rate, latency_p95_ms
                from source_health_snapshots
                order by source_key, created_at desc
                """
        );
    }

    private LiveQualityAnomaly anomaly(ResultSet rs, int rowNum) throws SQLException {
        return new LiveQualityAnomaly(
            rs.getObject("id", UUID.class),
            rs.getString("anomaly_type"),
            rs.getString("severity"),
            rs.getString("affected_surface"),
            rs.getString("affected_source"),
            rs.getString("recommended_action"),
            rs.getBigDecimal("observed_value"),
            rs.getBigDecimal("baseline_value"),
            rs.getBigDecimal("threshold_value"),
            readMap(rs.getString("evidence_json"))
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize anomaly JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to read anomaly JSON", exception);
        }
    }
}
