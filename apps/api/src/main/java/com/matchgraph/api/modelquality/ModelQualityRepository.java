package com.matchgraph.api.modelquality;

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
public class ModelQualityRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ModelQualityRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createCalibrationRun(CalibrationRequest request, int bucketCount) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into model_calibration_runs (
                    id, model_key, version_key, dataset_run_id, status, bucket_count
                )
                values (?, ?, ?, ?, 'RUNNING', ?)
                """,
            id,
            request.modelKey(),
            request.versionKey(),
            request.datasetRunId(),
            bucketCount
        );
        return id;
    }

    public void insertCalibrationBucket(UUID runId, int index, BigDecimal start, BigDecimal end, int count, BigDecimal predicted, BigDecimal reward, BigDecimal positiveRate, BigDecimal error, String status) {
        jdbcTemplate.update(
            """
                insert into model_calibration_buckets (
                    id, run_id, bucket_index, bucket_start, bucket_end, example_count,
                    predicted_average, observed_reward_average, observed_positive_rate,
                    calibration_error, confidence_status
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            UUID.randomUUID(),
            runId,
            index,
            start,
            end,
            count,
            predicted,
            reward,
            positiveRate,
            error,
            status
        );
    }

    public void completeCalibration(UUID runId, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update model_calibration_runs
                set status = 'COMPLETED',
                    summary_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            toJson(summary),
            runId
        );
    }

    public UUID createDriftRun(DriftRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into model_drift_runs (
                    id, baseline_dataset_run_id, candidate_dataset_run_id,
                    baseline_model_version, candidate_model_version, segment_key, status
                )
                values (?, ?, ?, ?, ?, ?, 'RUNNING')
                """,
            id,
            request.baselineDatasetRunId(),
            request.candidateDatasetRunId(),
            request.baselineModelVersion(),
            request.candidateModelVersion(),
            request.segmentKey()
        );
        return id;
    }

    public void insertDriftResult(UUID runId, String key, String type, BigDecimal psi, BigDecimal js, String status, Map<String, Object> detail) {
        jdbcTemplate.update(
            """
                insert into model_drift_results (
                    id, run_id, result_key, metric_type, psi_approx, js_approx, status, detail_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            key,
            type,
            psi,
            js,
            status,
            toJson(detail)
        );
    }

    public void completeDrift(UUID runId, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update model_drift_runs
                set status = 'COMPLETED',
                    summary_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            toJson(summary),
            runId
        );
    }

    public Optional<CalibrationRun> findCalibration(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, model_key, version_key, dataset_run_id, status, bucket_count,
                    summary_json::text as summary_json, created_at, completed_at
                from model_calibration_runs
                where id = ?
                """,
            this::mapCalibrationWithoutBuckets,
            runId
        ).stream().findFirst()
            .map(run -> new CalibrationRun(run.id(), run.modelKey(), run.versionKey(), run.datasetRunId(), run.status(), run.bucketCount(), run.summary(), run.createdAt(), run.completedAt(), calibrationBuckets(run.id())));
    }

    public List<CalibrationBucket> calibrationBuckets(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, run_id, bucket_index, bucket_start, bucket_end, example_count,
                    predicted_average, observed_reward_average, observed_positive_rate,
                    calibration_error, confidence_status, created_at
                from model_calibration_buckets
                where run_id = ?
                order by bucket_index
                """,
            this::mapCalibrationBucket,
            runId
        );
    }

    public Optional<DriftRun> findDrift(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, baseline_dataset_run_id, candidate_dataset_run_id, baseline_model_version,
                    candidate_model_version, segment_key, status, summary_json::text as summary_json,
                    created_at, completed_at
                from model_drift_runs
                where id = ?
                """,
            this::mapDriftWithoutResults,
            runId
        ).stream().findFirst()
            .map(run -> new DriftRun(run.id(), run.baselineDatasetRunId(), run.candidateDatasetRunId(), run.baselineModelVersion(), run.candidateModelVersion(), run.segmentKey(), run.status(), run.summary(), run.createdAt(), run.completedAt(), driftResults(run.id())));
    }

    public List<DriftResult> driftResults(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, run_id, result_key, metric_type, psi_approx, js_approx,
                    status, detail_json::text as detail_json, created_at
                from model_drift_results
                where run_id = ?
                order by result_key
                """,
            this::mapDriftResult,
            runId
        );
    }

    private CalibrationRun mapCalibrationWithoutBuckets(ResultSet rs, int rowNum) throws SQLException {
        return new CalibrationRun(
            rs.getObject("id", UUID.class),
            rs.getString("model_key"),
            rs.getString("version_key"),
            rs.getObject("dataset_run_id", UUID.class),
            rs.getString("status"),
            rs.getInt("bucket_count"),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            List.of()
        );
    }

    private CalibrationBucket mapCalibrationBucket(ResultSet rs, int rowNum) throws SQLException {
        return new CalibrationBucket(
            rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getInt("bucket_index"),
            rs.getBigDecimal("bucket_start"),
            rs.getBigDecimal("bucket_end"),
            rs.getInt("example_count"),
            rs.getBigDecimal("predicted_average"),
            rs.getBigDecimal("observed_reward_average"),
            rs.getBigDecimal("observed_positive_rate"),
            rs.getBigDecimal("calibration_error"),
            rs.getString("confidence_status"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private DriftRun mapDriftWithoutResults(ResultSet rs, int rowNum) throws SQLException {
        return new DriftRun(
            rs.getObject("id", UUID.class),
            rs.getObject("baseline_dataset_run_id", UUID.class),
            rs.getObject("candidate_dataset_run_id", UUID.class),
            rs.getString("baseline_model_version"),
            rs.getString("candidate_model_version"),
            rs.getString("segment_key"),
            rs.getString("status"),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            List.of()
        );
    }

    private DriftResult mapDriftResult(ResultSet rs, int rowNum) throws SQLException {
        return new DriftResult(
            rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getString("result_key"),
            rs.getString("metric_type"),
            rs.getBigDecimal("psi_approx"),
            rs.getBigDecimal("js_approx"),
            rs.getString("status"),
            map(rs.getString("detail_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored model quality JSON is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("model quality value must be JSON serializable", exception);
        }
    }
}
