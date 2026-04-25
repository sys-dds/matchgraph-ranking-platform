package com.matchgraph.api.explainability;

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
public class RankingExplainabilityRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RankingExplainabilityRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createRequest(RankingExplanationRequest request, String type) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into ranking_explanation_requests (
                    id, profile_id, candidate_profile_id, decision_log_id, feed_snapshot_id,
                    explanation_type, status, result_json
                )
                values (?, ?, ?, ?, ?, ?, 'RUNNING', '{}'::jsonb)
                """,
            id,
            request.profileId(),
            request.candidateProfileId(),
            request.decisionLogId(),
            request.feedSnapshotId(),
            type
        );
        return id;
    }

    public UUID complete(UUID requestId, RankingExplanationRequest request, String type, String evidenceStatus, Map<String, Object> result) {
        jdbcTemplate.update(
            """
                update ranking_explanation_requests
                set status = 'COMPLETED',
                    result_json = ?::jsonb
                where id = ?
                """,
            toJson(result),
            requestId
        );
        UUID resultId = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into ranking_explanation_results (
                    id, request_id, profile_id, candidate_profile_id, decision_log_id,
                    feed_snapshot_id, explanation_type, evidence_status, result_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            resultId,
            requestId,
            request.profileId(),
            request.candidateProfileId(),
            request.decisionLogId(),
            request.feedSnapshotId(),
            type,
            evidenceStatus,
            toJson(result)
        );
        return resultId;
    }

    public Optional<RowEvidence> findDecisionItem(UUID decisionLogId, UUID candidateProfileId) {
        return jdbcTemplate.query(
            """
                select l.profile_id, i.candidate_profile_id, l.retrieval_run_id, l.feature_snapshot_run_id,
                    i.feature_snapshot_id, l.id as decision_log_id, null::uuid as feed_snapshot_id,
                    l.ranking_version, i.position, i.final_score,
                    i.reasons_json::text as reasons_json,
                    i.diversity_adjustments_json::text as diversity_adjustments_json,
                    i.source_types_json::text as source_types_json
                from ranking_decision_logs l
                join ranking_decision_items i on i.decision_log_id = l.id
                where l.id = ?
                  and i.candidate_profile_id = ?
                """,
            this::mapEvidence,
            decisionLogId,
            candidateProfileId
        ).stream().findFirst();
    }

    public Optional<RowEvidence> findLatestShown(UUID profileId, UUID candidateProfileId) {
        return jdbcTemplate.query(
            """
                select fs.profile_id, fi.candidate_profile_id, fi.retrieval_run_id, fs.feature_snapshot_run_id,
                    fi.feature_snapshot_id, fi.ranking_decision_log_id as decision_log_id,
                    fi.feed_snapshot_id, fs.ranking_version, fi.position, fi.score as final_score,
                    fi.ranking_reasons_json::text as reasons_json,
                    fi.diversity_adjustments_json::text as diversity_adjustments_json,
                    fi.source_types_json::text as source_types_json
                from feed_items fi
                join feed_snapshots fs on fs.id = fi.feed_snapshot_id
                where fs.profile_id = ?
                  and fi.candidate_profile_id = ?
                order by fi.created_at desc
                limit 1
                """,
            this::mapEvidence,
            profileId,
            candidateProfileId
        ).stream().findFirst();
    }

    public Optional<RowEvidence> findLatestRetrieved(UUID profileId, UUID candidateProfileId) {
        return jdbcTemplate.query(
            """
                select r.profile_id, i.candidate_profile_id, r.id as retrieval_run_id,
                    null::uuid as feature_snapshot_run_id, null::uuid as feature_snapshot_id,
                    null::uuid as decision_log_id, null::uuid as feed_snapshot_id,
                    null::text as ranking_version, null::integer as position, null::numeric as final_score,
                    '[]'::text as reasons_json, '[]'::text as diversity_adjustments_json,
                    jsonb_build_array(i.source_type)::text as source_types_json
                from candidate_retrieval_runs r
                join candidate_retrieval_items i on i.retrieval_run_id = r.id
                where r.profile_id = ?
                  and i.candidate_profile_id = ?
                order by r.created_at desc
                limit 1
                """,
            this::mapEvidence,
            profileId,
            candidateProfileId
        ).stream().findFirst();
    }

    public Map<String, Object> featureValues(UUID featureSnapshotId) {
        if (featureSnapshotId == null) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                select feature_key, numeric_value, text_value, json_value::text as json_value, freshness_status
                from candidate_feature_values
                where snapshot_id = ?
                order by feature_key
                """,
            rs -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("numericValue", rs.getBigDecimal("numeric_value"));
                value.put("textValue", rs.getString("text_value"));
                value.put("jsonValue", rs.getString("json_value") == null ? null : map(rs.getString("json_value")));
                value.put("freshnessStatus", rs.getString("freshness_status"));
                values.put(rs.getString("feature_key"), value);
            },
            featureSnapshotId
        );
        return values;
    }

    private RowEvidence mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        return new RowEvidence(
            rs.getObject("profile_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getObject("retrieval_run_id", UUID.class),
            rs.getObject("feature_snapshot_run_id", UUID.class),
            rs.getObject("feature_snapshot_id", UUID.class),
            rs.getObject("decision_log_id", UUID.class),
            rs.getObject("feed_snapshot_id", UUID.class),
            rs.getString("ranking_version"),
            integerOrNull(rs, "position"),
            rs.getBigDecimal("final_score"),
            list(rs.getString("reasons_json")),
            list(rs.getString("diversity_adjustments_json")),
            list(rs.getString("source_types_json"))
        );
    }

    private Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private List<Object> list(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored explanation list json is invalid", exception);
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored explanation map json is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("explanation value must be JSON serializable", exception);
        }
    }

    public record RowEvidence(
        UUID profileId,
        UUID candidateProfileId,
        UUID retrievalRunId,
        UUID featureSnapshotRunId,
        UUID featureSnapshotId,
        UUID decisionLogId,
        UUID feedSnapshotId,
        String rankingVersion,
        Integer position,
        java.math.BigDecimal finalScore,
        List<Object> reasons,
        List<Object> diversityAdjustments,
        List<Object> sourceTypes
    ) {
    }
}
