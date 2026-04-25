package com.matchgraph.api.causal;

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
public class CausalEvaluationRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CausalEvaluationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insertPropensityLog(UUID exampleId, UUID decisionLogId, UUID feedSnapshotId, UUID feedItemId, UUID profileId, UUID candidateId, BigDecimal propensity, String source, Map<String, Object> detail) {
        jdbcTemplate.update(
            """
                insert into propensity_logs (
                    id, training_example_id, decision_log_id, feed_snapshot_id, feed_item_id,
                    profile_id, candidate_profile_id, propensity, propensity_source, detail_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            exampleId,
            decisionLogId,
            feedSnapshotId,
            feedItemId,
            profileId,
            candidateId,
            propensity,
            source,
            toJson(detail)
        );
    }

    public Optional<PropensityLog> findPropensity(UUID exampleId) {
        return jdbcTemplate.query(
            """
                select id, training_example_id, decision_log_id, feed_snapshot_id, feed_item_id,
                    profile_id, candidate_profile_id, propensity, propensity_source,
                    detail_json::text as detail_json, created_at
                from propensity_logs
                where training_example_id = ?
                order by created_at desc
                limit 1
                """,
            this::mapPropensity,
            exampleId
        ).stream().findFirst();
    }

    public UUID createRun(CausalEvaluationRequest request, BigDecimal maxWeight) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into causal_evaluation_runs (
                    id, dataset_run_id, k, use_ips_weights, max_weight, status
                )
                values (?, ?, ?, ?, ?, 'RUNNING')
                """,
            id,
            request.datasetRunId(),
            request.k() == null ? 10 : request.k(),
            request.useIpsWeights() == null || request.useIpsWeights(),
            maxWeight
        );
        return id;
    }

    public void insertResult(UUID runId, CausalEvaluationResult result) {
        jdbcTemplate.update(
            """
                insert into causal_evaluation_results (
                    id, run_id, ips_precision_at_k, ips_ndcg_at_k, weighted_average_reward,
                    effective_sample_size, propensity_coverage, excluded_due_to_missing_propensity,
                    missing_propensity_warning, high_variance_warning, metrics_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            result.ipsPrecisionAtK(),
            result.ipsNdcgAtK(),
            result.weightedAverageReward(),
            result.effectiveSampleSize(),
            result.propensityCoverage(),
            result.excludedDueToMissingPropensity(),
            result.missingPropensityWarning(),
            result.highVarianceWarning(),
            toJson(result.metrics())
        );
    }

    public void completeRun(UUID runId, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update causal_evaluation_runs
                set status = 'COMPLETED',
                    summary_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            toJson(summary),
            runId
        );
    }

    public Optional<CausalEvaluationRun> findRun(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, dataset_run_id, k, use_ips_weights, max_weight, status,
                    summary_json::text as summary_json, created_at, completed_at
                from causal_evaluation_runs
                where id = ?
                """,
            this::mapRunWithoutResult,
            runId
        ).stream().findFirst()
            .map(run -> new CausalEvaluationRun(run.id(), run.datasetRunId(), run.k(), run.useIpsWeights(), run.maxWeight(), run.status(), run.summary(), run.createdAt(), run.completedAt(), result(run.id()).orElse(null)));
    }

    public Optional<CausalEvaluationResult> result(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, run_id, ips_precision_at_k, ips_ndcg_at_k, weighted_average_reward,
                    effective_sample_size, propensity_coverage, excluded_due_to_missing_propensity,
                    missing_propensity_warning, high_variance_warning, metrics_json::text as metrics_json,
                    created_at
                from causal_evaluation_results
                where run_id = ?
                """,
            this::mapResult,
            runId
        ).stream().findFirst();
    }

    private PropensityLog mapPropensity(ResultSet rs, int rowNum) throws SQLException {
        return new PropensityLog(
            rs.getObject("id", UUID.class),
            rs.getObject("training_example_id", UUID.class),
            rs.getObject("decision_log_id", UUID.class),
            rs.getObject("feed_snapshot_id", UUID.class),
            rs.getObject("feed_item_id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getBigDecimal("propensity"),
            rs.getString("propensity_source"),
            map(rs.getString("detail_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private CausalEvaluationRun mapRunWithoutResult(ResultSet rs, int rowNum) throws SQLException {
        return new CausalEvaluationRun(
            rs.getObject("id", UUID.class),
            rs.getObject("dataset_run_id", UUID.class),
            rs.getInt("k"),
            rs.getBoolean("use_ips_weights"),
            rs.getBigDecimal("max_weight"),
            rs.getString("status"),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            null
        );
    }

    private CausalEvaluationResult mapResult(ResultSet rs, int rowNum) throws SQLException {
        return new CausalEvaluationResult(
            rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getBigDecimal("ips_precision_at_k"),
            rs.getBigDecimal("ips_ndcg_at_k"),
            rs.getBigDecimal("weighted_average_reward"),
            rs.getBigDecimal("effective_sample_size"),
            rs.getBigDecimal("propensity_coverage"),
            rs.getInt("excluded_due_to_missing_propensity"),
            rs.getBoolean("missing_propensity_warning"),
            rs.getBoolean("high_variance_warning"),
            map(rs.getString("metrics_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored causal JSON is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("causal value must be JSON serializable", exception);
        }
    }
}
