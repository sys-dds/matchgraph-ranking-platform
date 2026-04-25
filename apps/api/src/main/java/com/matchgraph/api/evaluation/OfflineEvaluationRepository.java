package com.matchgraph.api.evaluation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
public class OfflineEvaluationRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OfflineEvaluationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public OfflineEvaluationRun createRun(UUID id, OfflineEvaluationRequest request, int k) {
        return jdbcTemplate.queryForObject(
            """
                insert into offline_evaluation_runs (
                    id, ranking_version, experiment_key, from_time, to_time, k, status, request_json
                )
                values (?, ?, ?, ?, ?, ?, 'RUNNING', ?::jsonb)
                returning id, ranking_version, experiment_key, from_time, to_time, k, status,
                    created_at, completed_at, request_json::text as request_json
                """,
            this::mapRun,
            id,
            blankToNull(request.rankingVersion()),
            blankToNull(request.experimentKey()),
            request.from(),
            request.to(),
            k,
            toJson(requestJson(request, k))
        );
    }

    public void completeRun(UUID runId) {
        jdbcTemplate.update("update offline_evaluation_runs set status = 'COMPLETED', completed_at = now() where id = ?", runId);
    }

    public OfflineEvaluationResult insertResult(UUID runId, EvaluationStats stats) {
        return jdbcTemplate.queryForObject(
            """
                insert into offline_evaluation_results (
                    id, run_id, precision_at_k, recall_at_k, mrr, ndcg_at_k, coverage,
                    diversity, negative_signal_penalty, evaluated_decision_count,
                    labelled_decision_count, unlabelled_decision_count, stale_embedding_count, result_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                returning id, run_id, precision_at_k, recall_at_k, mrr, ndcg_at_k, coverage,
                    diversity, negative_signal_penalty, evaluated_decision_count,
                    labelled_decision_count, unlabelled_decision_count, stale_embedding_count,
                    result_json::text as result_json, created_at
                """,
            this::mapResult,
            UUID.randomUUID(),
            runId,
            stats.precisionAtK(),
            stats.recallAtK(),
            stats.mrr(),
            stats.ndcgAtK(),
            stats.coverage(),
            stats.diversity(),
            stats.negativeSignalPenalty(),
            stats.evaluatedDecisionCount(),
            stats.labelledDecisionCount(),
            stats.unlabelledDecisionCount(),
            stats.staleEmbeddingCount(),
            toJson(stats.result())
        );
    }

    public Optional<OfflineEvaluationRun> run(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, ranking_version, experiment_key, from_time, to_time, k, status,
                    created_at, completed_at, request_json::text as request_json
                from offline_evaluation_runs
                where id = ?
                """,
            this::mapRun,
            runId
        ).stream().findFirst();
    }

    public Optional<OfflineEvaluationResult> result(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, run_id, precision_at_k, recall_at_k, mrr, ndcg_at_k, coverage,
                    diversity, negative_signal_penalty, evaluated_decision_count,
                    labelled_decision_count, unlabelled_decision_count, stale_embedding_count,
                    result_json::text as result_json, created_at
                from offline_evaluation_results
                where run_id = ?
                """,
            this::mapResult,
            runId
        ).stream().findFirst();
    }

    public List<DecisionLabelRow> decisionRows(OfflineEvaluationRequest request, int k) {
        return jdbcTemplate.query(
            """
                select l.id as decision_log_id,
                    l.profile_id,
                    i.candidate_profile_id,
                    i.position,
                    i.source_types_json::text as source_types_json,
                    c.feature_freshness_status,
                    e.event_type
                from ranking_decision_logs l
                join ranking_decision_items i on i.decision_log_id = l.id
                join candidate_feature_snapshots c on c.id = i.feature_snapshot_id
                left join lateral (
                    select event_type
                    from interaction_events event
                    where event.actor_profile_id = l.profile_id
                      and event.target_profile_id = i.candidate_profile_id
                      and event.occurred_at >= l.created_at
                      and (event.retrieval_run_id is null or event.retrieval_run_id = l.retrieval_run_id)
                      and (event.ranking_version is null or event.ranking_version = l.ranking_version)
                      and (event.feed_position is null or event.feed_position = i.position)
                    order by event.occurred_at desc, event.created_at desc
                    limit 1
                ) e on true
                where i.position <= ?
                  and (?::text is null or l.ranking_version = ?)
                  and (?::timestamptz is null or l.created_at >= ?)
                  and (?::timestamptz is null or l.created_at <= ?)
                  and (?::text is null or l.ranking_context_json ->> 'experimentKey' = ?)
                order by l.created_at, l.id, i.position
                """,
            (rs, rowNum) -> new DecisionLabelRow(
                rs.getObject("decision_log_id", UUID.class),
                rs.getObject("profile_id", UUID.class),
                rs.getObject("candidate_profile_id", UUID.class),
                rs.getInt("position"),
                rs.getString("source_types_json"),
                rs.getString("feature_freshness_status"),
                rs.getString("event_type")
            ),
            k,
            blankToNull(request.rankingVersion()),
            blankToNull(request.rankingVersion()),
            request.from(),
            request.from(),
            request.to(),
            request.to(),
            blankToNull(request.experimentKey()),
            blankToNull(request.experimentKey())
        );
    }

    private OfflineEvaluationRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new OfflineEvaluationRun(
            rs.getObject("id", UUID.class),
            rs.getString("ranking_version"),
            rs.getString("experiment_key"),
            rs.getObject("from_time", OffsetDateTime.class),
            rs.getObject("to_time", OffsetDateTime.class),
            rs.getInt("k"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            map(rs.getString("request_json"))
        );
    }

    private OfflineEvaluationResult mapResult(ResultSet rs, int rowNum) throws SQLException {
        return new OfflineEvaluationResult(
            rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getBigDecimal("precision_at_k"),
            rs.getBigDecimal("recall_at_k"),
            rs.getBigDecimal("mrr"),
            rs.getBigDecimal("ndcg_at_k"),
            rs.getBigDecimal("coverage"),
            rs.getBigDecimal("diversity"),
            rs.getBigDecimal("negative_signal_penalty"),
            rs.getInt("evaluated_decision_count"),
            rs.getInt("labelled_decision_count"),
            rs.getInt("unlabelled_decision_count"),
            rs.getInt("stale_embedding_count"),
            map(rs.getString("result_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("evaluation value must be JSON serializable", exception);
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored evaluation json is invalid", exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Map<String, Object> requestJson(OfflineEvaluationRequest request, int k) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("rankingVersion", blankToNull(request.rankingVersion()));
        json.put("experimentKey", blankToNull(request.experimentKey()));
        json.put("k", k);
        return json;
    }

    public record DecisionLabelRow(
        UUID decisionLogId,
        UUID profileId,
        UUID candidateProfileId,
        int position,
        String sourceTypesJson,
        String featureFreshnessStatus,
        String eventType
    ) {
    }

    public record EvaluationStats(
        BigDecimal precisionAtK,
        BigDecimal recallAtK,
        BigDecimal mrr,
        BigDecimal ndcgAtK,
        BigDecimal coverage,
        BigDecimal diversity,
        BigDecimal negativeSignalPenalty,
        int evaluatedDecisionCount,
        int labelledDecisionCount,
        int unlabelledDecisionCount,
        int staleEmbeddingCount,
        Map<String, Object> result
    ) {
    }
}
