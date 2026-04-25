package com.matchgraph.api.rolloutgate;

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
public class ModelRolloutGateRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ModelRolloutGateRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createRun(ModelRolloutGateRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into model_rollout_gate_runs (
                    id, candidate_model_key, candidate_version_key, baseline_model_key,
                    baseline_version_key, status, recommendation, config_json
                )
                values (?, ?, ?, ?, ?, 'RUNNING', 'HOLD', ?::jsonb)
                """,
            id,
            request.candidateModelKey(),
            request.candidateVersionKey(),
            request.baselineModelKey(),
            request.baselineVersionKey(),
            toJson(request.config() == null ? Map.of() : request.config())
        );
        return id;
    }

    public void insertCheck(UUID runId, GateCheck check) {
        jdbcTemplate.update(
            """
                insert into model_rollout_gate_checks (
                    id, gate_run_id, check_key, status, required, observed_value, threshold_value, detail_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            check.checkKey(),
            check.status(),
            check.required(),
            check.observedValue(),
            check.thresholdValue(),
            toJson(check.detail())
        );
    }

    public void completeRun(UUID runId, String recommendation, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update model_rollout_gate_runs
                set status = 'COMPLETED', recommendation = ?, summary_json = ?::jsonb, completed_at = now()
                where id = ?
                """,
            recommendation,
            toJson(summary),
            runId
        );
    }

    public void insertReport(UUID runId, String modelKey, String versionKey, String recommendation, Map<String, Object> report) {
        jdbcTemplate.update(
            """
                insert into model_acceptance_reports (id, gate_run_id, model_key, version_key, recommendation, report_json)
                values (?, ?, ?, ?, ?, ?::jsonb)
                on conflict (gate_run_id)
                do update set recommendation = excluded.recommendation, report_json = excluded.report_json
                """,
            UUID.randomUUID(),
            runId,
            modelKey,
            versionKey,
            recommendation,
            toJson(report)
        );
    }

    public Optional<ModelRolloutGateRun> findRun(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, candidate_model_key, candidate_version_key, baseline_model_key, baseline_version_key,
                    status, recommendation, config_json::text as config_json, summary_json::text as summary_json,
                    created_at, completed_at
                from model_rollout_gate_runs
                where id = ?
                """,
            this::mapRunWithoutChecks,
            runId
        ).stream().findFirst()
            .map(run -> new ModelRolloutGateRun(run.id(), run.candidateModelKey(), run.candidateVersionKey(), run.baselineModelKey(), run.baselineVersionKey(), run.status(), run.recommendation(), run.config(), run.summary(), run.createdAt(), run.completedAt(), checks(run.id())));
    }

    public Optional<ModelRolloutGateRun> latestRun(String modelKey, String versionKey) {
        return jdbcTemplate.query(
            """
                select id, candidate_model_key, candidate_version_key, baseline_model_key, baseline_version_key,
                    status, recommendation, config_json::text as config_json, summary_json::text as summary_json,
                    created_at, completed_at
                from model_rollout_gate_runs
                where candidate_model_key = ? and candidate_version_key = ?
                order by created_at desc
                limit 1
                """,
            this::mapRunWithoutChecks,
            modelKey,
            versionKey
        ).stream().findFirst()
            .map(run -> new ModelRolloutGateRun(run.id(), run.candidateModelKey(), run.candidateVersionKey(), run.baselineModelKey(), run.baselineVersionKey(), run.status(), run.recommendation(), run.config(), run.summary(), run.createdAt(), run.completedAt(), checks(run.id())));
    }

    public List<ModelRolloutGateCheck> checks(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, gate_run_id, check_key, status, required, observed_value, threshold_value,
                    detail_json::text as detail_json, created_at
                from model_rollout_gate_checks
                where gate_run_id = ?
                order by check_key
                """,
            this::mapCheck,
            runId
        );
    }

    public Optional<ModelAcceptanceReport> report(String modelKey, String versionKey) {
        return jdbcTemplate.query(
            """
                select id, gate_run_id, model_key, version_key, recommendation,
                    report_json::text as report_json, created_at
                from model_acceptance_reports
                where model_key = ? and version_key = ?
                order by created_at desc
                limit 1
                """,
            this::mapReport,
            modelKey,
            versionKey
        ).stream().findFirst();
    }

    public boolean exists(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private ModelRolloutGateRun mapRunWithoutChecks(ResultSet rs, int rowNum) throws SQLException {
        return new ModelRolloutGateRun(
            rs.getObject("id", UUID.class),
            rs.getString("candidate_model_key"),
            rs.getString("candidate_version_key"),
            rs.getString("baseline_model_key"),
            rs.getString("baseline_version_key"),
            rs.getString("status"),
            rs.getString("recommendation"),
            map(rs.getString("config_json")),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            List.of()
        );
    }

    private ModelRolloutGateCheck mapCheck(ResultSet rs, int rowNum) throws SQLException {
        return new ModelRolloutGateCheck(
            rs.getObject("id", UUID.class),
            rs.getObject("gate_run_id", UUID.class),
            rs.getString("check_key"),
            rs.getString("status"),
            rs.getBoolean("required"),
            rs.getString("observed_value"),
            rs.getString("threshold_value"),
            map(rs.getString("detail_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private ModelAcceptanceReport mapReport(ResultSet rs, int rowNum) throws SQLException {
        return new ModelAcceptanceReport(
            rs.getObject("id", UUID.class),
            rs.getObject("gate_run_id", UUID.class),
            rs.getString("model_key"),
            rs.getString("version_key"),
            rs.getString("recommendation"),
            map(rs.getString("report_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored rollout gate JSON is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("rollout gate value must be JSON serializable", exception);
        }
    }

    public record GateCheck(String checkKey, String status, boolean required, String observedValue, String thresholdValue, Map<String, Object> detail) {
    }
}
