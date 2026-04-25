package com.matchgraph.api.synthetic;

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
public class SyntheticPopulationRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SyntheticPopulationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createRun(long seed, int profileCount, int clusterCount, BigDecimal density, Map<String, Object> config) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into synthetic_population_runs (
                    id, random_seed, profile_count, cluster_count, compatibility_density,
                    status, config_json
                )
                values (?, ?, ?, ?, ?, 'RUNNING', ?::jsonb)
                """,
            id,
            seed,
            profileCount,
            clusterCount,
            density,
            toJson(config == null ? Map.of() : config)
        );
        return id;
    }

    public void completeRun(UUID runId, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update synthetic_population_runs
                set status = 'COMPLETED',
                    summary_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            toJson(summary),
            runId
        );
    }

    public void insertProfile(UUID runId, UUID profileId, String clusterId, String locationCluster, Map<String, Object> vector) {
        jdbcTemplate.update(
            """
                insert into synthetic_profiles (
                    id, run_id, profile_id, cluster_id, location_cluster, synthetic_preference_vector_json
                )
                values (?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            profileId,
            clusterId,
            locationCluster,
            toJson(vector)
        );
    }

    public void insertLabel(UUID runId, UUID actorProfileId, UUID candidateProfileId, String label, BigDecimal relevance, Map<String, Object> reason) {
        jdbcTemplate.update(
            """
                insert into synthetic_ground_truth_labels (
                    id, run_id, actor_profile_id, candidate_profile_id,
                    compatibility_label, expected_relevance, label_reason_json
                )
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                on conflict (run_id, actor_profile_id, candidate_profile_id)
                do update set compatibility_label = excluded.compatibility_label,
                    expected_relevance = excluded.expected_relevance,
                    label_reason_json = excluded.label_reason_json
                """,
            UUID.randomUUID(),
            runId,
            actorProfileId,
            candidateProfileId,
            label,
            relevance,
            toJson(reason)
        );
    }

    public Optional<SyntheticPopulationRun> findRun(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, random_seed, profile_count, cluster_count, compatibility_density,
                    status, config_json::text as config_json, summary_json::text as summary_json,
                    created_at, completed_at
                from synthetic_population_runs
                where id = ?
                """,
            this::mapRunWithoutProfiles,
            runId
        ).stream().findFirst()
            .map(run -> new SyntheticPopulationRun(
                run.id(),
                run.randomSeed(),
                run.profileCount(),
                run.clusterCount(),
                run.compatibilityDensity(),
                run.status(),
                run.config(),
                run.summary(),
                run.createdAt(),
                run.completedAt(),
                profiles(run.id())
            ));
    }

    public List<SyntheticProfile> profiles(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, run_id, profile_id, cluster_id, location_cluster,
                    synthetic_preference_vector_json::text as synthetic_preference_vector_json, created_at
                from synthetic_profiles
                where run_id = ?
                order by cluster_id, profile_id
                """,
            this::mapProfile,
            runId
        );
    }

    public List<SyntheticGroundTruthLabel> labels(UUID runId, UUID actorProfileId) {
        return jdbcTemplate.query(
            """
                select id, run_id, actor_profile_id, candidate_profile_id,
                    compatibility_label, expected_relevance, label_reason_json::text as label_reason_json,
                    created_at
                from synthetic_ground_truth_labels
                where run_id = ?
                  and actor_profile_id = ?
                order by expected_relevance desc, candidate_profile_id
                """,
            this::mapLabel,
            runId,
            actorProfileId
        );
    }

    public UUID createEvaluationRun(SyntheticEvaluationRequest request, Map<String, Object> requestJson) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into synthetic_evaluation_runs (
                    id, synthetic_population_run_id, ranking_version, decision_log_id, k, status, request_json
                )
                values (?, ?, ?, ?, ?, 'RUNNING', ?::jsonb)
                """,
            id,
            request.syntheticPopulationRunId(),
            request.rankingVersion(),
            request.decisionLogId(),
            request.k() == null ? 10 : request.k(),
            toJson(requestJson)
        );
        return id;
    }

    public void completeEvaluationRun(UUID runId, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update synthetic_evaluation_runs
                set status = 'COMPLETED',
                    summary_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            toJson(summary),
            runId
        );
    }

    public UUID insertEvaluationResult(
        UUID evaluationRunId,
        BigDecimal precision,
        BigDecimal ndcg,
        BigDecimal mrr,
        BigDecimal clusterCoverage,
        BigDecimal longTailCoverage,
        Map<String, Object> exposureDistribution,
        int safetyViolationCount,
        Map<String, Object> metrics
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into synthetic_evaluation_results (
                    id, evaluation_run_id, precision_at_k, ndcg_at_k, mrr,
                    cluster_coverage, long_tail_coverage, exposure_distribution_json,
                    safety_violation_count, metrics_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb)
                """,
            id,
            evaluationRunId,
            precision,
            ndcg,
            mrr,
            clusterCoverage,
            longTailCoverage,
            toJson(exposureDistribution),
            safetyViolationCount,
            toJson(metrics)
        );
        return id;
    }

    public Optional<SyntheticEvaluationRun> findEvaluationRun(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, synthetic_population_run_id, ranking_version, decision_log_id, k,
                    status, request_json::text as request_json, summary_json::text as summary_json,
                    created_at, completed_at
                from synthetic_evaluation_runs
                where id = ?
                """,
            this::mapEvaluationRunWithoutResult,
            runId
        ).stream().findFirst()
            .map(run -> new SyntheticEvaluationRun(
                run.id(),
                run.syntheticPopulationRunId(),
                run.rankingVersion(),
                run.decisionLogId(),
                run.k(),
                run.status(),
                run.request(),
                run.summary(),
                run.createdAt(),
                run.completedAt(),
                evaluationResult(run.id()).orElse(null)
            ));
    }

    public Optional<SyntheticEvaluationResult> evaluationResult(UUID evaluationRunId) {
        return jdbcTemplate.query(
            """
                select id, evaluation_run_id, precision_at_k, ndcg_at_k, mrr,
                    cluster_coverage, long_tail_coverage, exposure_distribution_json::text as exposure_distribution_json,
                    safety_violation_count, metrics_json::text as metrics_json, created_at
                from synthetic_evaluation_results
                where evaluation_run_id = ?
                """,
            this::mapEvaluationResult,
            evaluationRunId
        ).stream().findFirst();
    }

    public DecisionFact decision(UUID decisionLogId) {
        return jdbcTemplate.queryForObject(
            """
                select id, profile_id, ranking_version
                from ranking_decision_logs
                where id = ?
                """,
            (rs, rowNum) -> new DecisionFact(
                rs.getObject("id", UUID.class),
                rs.getObject("profile_id", UUID.class),
                rs.getString("ranking_version")
            ),
            decisionLogId
        );
    }

    public List<DecisionItemFact> decisionItems(UUID decisionLogId, int k) {
        return jdbcTemplate.query(
            """
                select i.candidate_profile_id, i.position, i.final_score,
                    sp.cluster_id,
                    coalesce(e.exposure_count, 0) as exposure_count
                from ranking_decision_items i
                left join synthetic_profiles sp on sp.profile_id = i.candidate_profile_id
                left join (
                    select candidate_profile_id, count(*)::int as exposure_count
                    from candidate_exposure_events
                    group by candidate_profile_id
                ) e on e.candidate_profile_id = i.candidate_profile_id
                where i.decision_log_id = ?
                order by i.position
                limit ?
                """,
            (rs, rowNum) -> new DecisionItemFact(
                rs.getObject("candidate_profile_id", UUID.class),
                rs.getInt("position"),
                rs.getBigDecimal("final_score"),
                rs.getString("cluster_id"),
                rs.getInt("exposure_count")
            ),
            decisionLogId,
            k
        );
    }

    private SyntheticPopulationRun mapRunWithoutProfiles(ResultSet rs, int rowNum) throws SQLException {
        return new SyntheticPopulationRun(
            rs.getObject("id", UUID.class),
            rs.getLong("random_seed"),
            rs.getInt("profile_count"),
            rs.getInt("cluster_count"),
            rs.getBigDecimal("compatibility_density"),
            rs.getString("status"),
            map(rs.getString("config_json")),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            List.of()
        );
    }

    private SyntheticProfile mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new SyntheticProfile(
            rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getString("cluster_id"),
            rs.getString("location_cluster"),
            map(rs.getString("synthetic_preference_vector_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private SyntheticGroundTruthLabel mapLabel(ResultSet rs, int rowNum) throws SQLException {
        return new SyntheticGroundTruthLabel(
            rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getObject("actor_profile_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getString("compatibility_label"),
            rs.getBigDecimal("expected_relevance"),
            map(rs.getString("label_reason_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private SyntheticEvaluationRun mapEvaluationRunWithoutResult(ResultSet rs, int rowNum) throws SQLException {
        return new SyntheticEvaluationRun(
            rs.getObject("id", UUID.class),
            rs.getObject("synthetic_population_run_id", UUID.class),
            rs.getString("ranking_version"),
            rs.getObject("decision_log_id", UUID.class),
            rs.getInt("k"),
            rs.getString("status"),
            map(rs.getString("request_json")),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            null
        );
    }

    private SyntheticEvaluationResult mapEvaluationResult(ResultSet rs, int rowNum) throws SQLException {
        return new SyntheticEvaluationResult(
            rs.getObject("id", UUID.class),
            rs.getObject("evaluation_run_id", UUID.class),
            rs.getBigDecimal("precision_at_k"),
            rs.getBigDecimal("ndcg_at_k"),
            rs.getBigDecimal("mrr"),
            rs.getBigDecimal("cluster_coverage"),
            rs.getBigDecimal("long_tail_coverage"),
            map(rs.getString("exposure_distribution_json")),
            rs.getInt("safety_violation_count"),
            map(rs.getString("metrics_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored synthetic json is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("synthetic value must be JSON serializable", exception);
        }
    }

    public record DecisionFact(UUID id, UUID profileId, String rankingVersion) {
    }

    public record DecisionItemFact(UUID candidateProfileId, int position, BigDecimal finalScore, String clusterId, int exposureCount) {
    }
}
