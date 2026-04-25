package com.matchgraph.api.evaluation;

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
public class CounterfactualEvaluationRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CounterfactualEvaluationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public CounterfactualEvaluationRun createRun(UUID id, CounterfactualEvaluationRequest request, int k) {
        return jdbcTemplate.queryForObject(
            """
                insert into counterfactual_evaluation_runs (
                    id, baseline_decision_log_id, candidate_ranking_version, k, status
                )
                values (?, ?, ?, ?, 'RUNNING')
                returning id, baseline_decision_log_id, candidate_ranking_version, k, status,
                    summary_json::text as summary_json, created_at, completed_at
                """,
            this::mapRun,
            id,
            request.baselineDecisionLogId(),
            request.candidateRankingVersion(),
            k
        );
    }

    public void completeRun(UUID runId, Map<String, Object> summary) {
        jdbcTemplate.update(
            "update counterfactual_evaluation_runs set status = 'COMPLETED', summary_json = ?::jsonb, completed_at = now() where id = ?",
            toJson(summary),
            runId
        );
    }

    public CounterfactualEvaluationItem insertItem(
        UUID runId,
        UUID candidateProfileId,
        Integer originalPosition,
        Integer counterfactualPosition,
        BigDecimal originalScore,
        BigDecimal counterfactualScore,
        String topKChange,
        String labelEventType,
        Map<String, Object> metricDelta
    ) {
        Integer positionDelta = originalPosition == null || counterfactualPosition == null ? null : originalPosition - counterfactualPosition;
        return jdbcTemplate.queryForObject(
            """
                insert into counterfactual_evaluation_items (
                    id, run_id, candidate_profile_id, original_position, counterfactual_position,
                    original_score, counterfactual_score, position_delta, top_k_change,
                    label_event_type, metric_delta_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                returning id, run_id, candidate_profile_id, original_position, counterfactual_position,
                    original_score, counterfactual_score, position_delta, top_k_change,
                    label_event_type, metric_delta_json::text as metric_delta_json, created_at
                """,
            this::mapItem,
            UUID.randomUUID(),
            runId,
            candidateProfileId,
            originalPosition,
            counterfactualPosition,
            originalScore,
            counterfactualScore,
            positionDelta,
            topKChange,
            labelEventType,
            toJson(metricDelta)
        );
    }

    public Optional<CounterfactualEvaluationRun> run(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, baseline_decision_log_id, candidate_ranking_version, k, status,
                    summary_json::text as summary_json, created_at, completed_at
                from counterfactual_evaluation_runs
                where id = ?
                """,
            this::mapRun,
            runId
        ).stream().findFirst();
    }

    public List<CounterfactualEvaluationItem> items(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, run_id, candidate_profile_id, original_position, counterfactual_position,
                    original_score, counterfactual_score, position_delta, top_k_change,
                    label_event_type, metric_delta_json::text as metric_delta_json, created_at
                from counterfactual_evaluation_items
                where run_id = ?
                order by coalesce(counterfactual_position, original_position), candidate_profile_id
                """,
            this::mapItem,
            runId
        );
    }

    public String label(UUID actorProfileId, UUID candidateProfileId, OffsetDateTime since) {
        return jdbcTemplate.queryForList(
            """
                select event_type
                from interaction_events
                where actor_profile_id = ?
                  and target_profile_id = ?
                  and occurred_at >= ?
                order by occurred_at desc
                limit 1
                """,
            String.class,
            actorProfileId,
            candidateProfileId,
            since
        ).stream().findFirst().orElse(null);
    }

    private CounterfactualEvaluationRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new CounterfactualEvaluationRun(
            rs.getObject("id", UUID.class),
            rs.getObject("baseline_decision_log_id", UUID.class),
            rs.getString("candidate_ranking_version"),
            rs.getInt("k"),
            rs.getString("status"),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class)
        );
    }

    private CounterfactualEvaluationItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new CounterfactualEvaluationItem(
            rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            (Integer) rs.getObject("original_position"),
            (Integer) rs.getObject("counterfactual_position"),
            rs.getBigDecimal("original_score"),
            rs.getBigDecimal("counterfactual_score"),
            (Integer) rs.getObject("position_delta"),
            rs.getString("top_k_change"),
            rs.getString("label_event_type"),
            map(rs.getString("metric_delta_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("counterfactual value must be JSON serializable", exception);
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored counterfactual json is invalid", exception);
        }
    }
}
