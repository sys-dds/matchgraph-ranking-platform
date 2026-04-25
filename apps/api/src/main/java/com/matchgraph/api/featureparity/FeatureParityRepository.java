package com.matchgraph.api.featureparity;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeatureParityRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FeatureParityRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createRun(FeatureParityCheckRequest request, Map<String, Object> toleranceConfig) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into feature_parity_runs (
                    id, dataset_run_id, decision_log_id, status, tolerance_config_json
                )
                values (?, ?, ?, 'RUNNING', ?::jsonb)
                """,
            id,
            request.datasetRunId(),
            request.decisionLogId(),
            toJson(toleranceConfig)
        );
        return id;
    }

    public void insertResult(
        UUID runId,
        UUID trainingExampleId,
        String featureName,
        Object onlineValue,
        Object offlineValue,
        BigDecimal numericDelta,
        String status,
        Map<String, Object> detail
    ) {
        jdbcTemplate.update(
            """
                insert into feature_parity_results (
                    id, run_id, training_example_id, feature_name, online_value_json,
                    offline_value_json, numeric_delta, status, detail_json
                )
                values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            trainingExampleId,
            featureName,
            toJson(onlineValue),
            toJson(offlineValue),
            numericDelta,
            status,
            toJson(detail)
        );
    }

    public void completeRun(UUID runId, Summary summary) {
        jdbcTemplate.update(
            """
                update feature_parity_runs
                set status = 'COMPLETED',
                    compared_count = ?,
                    matched_count = ?,
                    skewed_count = ?,
                    missing_online_count = ?,
                    missing_offline_count = ?,
                    not_comparable_count = ?,
                    summary_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            summary.comparedCount(),
            summary.matchedCount(),
            summary.skewedCount(),
            summary.missingOnlineCount(),
            summary.missingOfflineCount(),
            summary.notComparableCount(),
            toJson(summary.summary()),
            runId
        );
    }

    public Optional<FeatureParityRun> findRun(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, dataset_run_id, decision_log_id, status, compared_count, matched_count,
                    skewed_count, missing_online_count, missing_offline_count, not_comparable_count,
                    tolerance_config_json::text as tolerance_config_json,
                    summary_json::text as summary_json, created_at, completed_at
                from feature_parity_runs
                where id = ?
                """,
            this::mapRunWithoutResults,
            runId
        ).stream().findFirst()
            .map(run -> new FeatureParityRun(
                run.id(),
                run.datasetRunId(),
                run.decisionLogId(),
                run.status(),
                run.comparedCount(),
                run.matchedCount(),
                run.skewedCount(),
                run.missingOnlineCount(),
                run.missingOfflineCount(),
                run.notComparableCount(),
                run.toleranceConfig(),
                run.summary(),
                run.createdAt(),
                run.completedAt(),
                results(run.id())
            ));
    }

    public List<FeatureParityResult> results(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, run_id, training_example_id, feature_name,
                    online_value_json::text as online_value_json,
                    offline_value_json::text as offline_value_json,
                    numeric_delta, status, detail_json::text as detail_json, created_at
                from feature_parity_results
                where run_id = ?
                order by feature_name, created_at
                """,
            this::mapResult,
            runId
        );
    }

    private FeatureParityRun mapRunWithoutResults(ResultSet rs, int rowNum) throws SQLException {
        return new FeatureParityRun(
            rs.getObject("id", UUID.class),
            rs.getObject("dataset_run_id", UUID.class),
            rs.getObject("decision_log_id", UUID.class),
            rs.getString("status"),
            rs.getInt("compared_count"),
            rs.getInt("matched_count"),
            rs.getInt("skewed_count"),
            rs.getInt("missing_online_count"),
            rs.getInt("missing_offline_count"),
            rs.getInt("not_comparable_count"),
            map(rs.getString("tolerance_config_json")),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            List.of()
        );
    }

    private FeatureParityResult mapResult(ResultSet rs, int rowNum) throws SQLException {
        return new FeatureParityResult(
            rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getObject("training_example_id", UUID.class),
            rs.getString("feature_name"),
            object(rs.getString("online_value_json")),
            object(rs.getString("offline_value_json")),
            rs.getBigDecimal("numeric_delta"),
            rs.getString("status"),
            map(rs.getString("detail_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored feature parity JSON is invalid", exception);
        }
    }

    private Object object(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored feature parity value JSON is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("feature parity value must be JSON serializable", exception);
        }
    }

    public record Summary(
        int comparedCount,
        int matchedCount,
        int skewedCount,
        int missingOnlineCount,
        int missingOfflineCount,
        int notComparableCount,
        Map<String, Object> summary
    ) {
    }
}
