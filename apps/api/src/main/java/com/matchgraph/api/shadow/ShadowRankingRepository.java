package com.matchgraph.api.shadow;

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
public class ShadowRankingRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ShadowRankingRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createRun(
        UUID profileId,
        UUID baselineDecisionLogId,
        String championRankingVersion,
        String challengerRankingVersion,
        UUID featureSnapshotRunId,
        Map<String, Object> rankingContext
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into shadow_ranking_runs (
                    id, profile_id, baseline_decision_log_id, champion_ranking_version,
                    challenger_ranking_version, feature_snapshot_run_id, ranking_context_json, status
                )
                values (?, ?, ?, ?, ?, ?, ?::jsonb, 'RUNNING')
                """,
            id,
            profileId,
            baselineDecisionLogId,
            championRankingVersion,
            challengerRankingVersion,
            featureSnapshotRunId,
            toJson(rankingContext == null ? Map.of() : rankingContext)
        );
        return id;
    }

    public void completeRun(UUID runId, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update shadow_ranking_runs
                set status = 'COMPLETED',
                    summary_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            toJson(summary),
            runId
        );
    }

    public void insertItem(UUID shadowRunId, ShadowRankingItem item) {
        jdbcTemplate.update(
            """
                insert into shadow_ranking_items (
                    id, shadow_run_id, candidate_profile_id, champion_position, challenger_position,
                    champion_score, challenger_score, position_delta, score_delta, reason_delta_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            item.id(),
            shadowRunId,
            item.candidateProfileId(),
            item.championPosition(),
            item.challengerPosition(),
            item.championScore(),
            item.challengerScore(),
            item.positionDelta(),
            item.scoreDelta(),
            item.reasonDelta() == null ? "{}" : toJson(item.reasonDelta())
        );
    }

    public Optional<ShadowRankingRun> findRun(UUID runId) {
        List<ShadowRankingRun> runs = jdbcTemplate.query(
            """
                select id, profile_id, baseline_decision_log_id, champion_ranking_version,
                    challenger_ranking_version, feature_snapshot_run_id,
                    ranking_context_json::text as ranking_context_json, status,
                    summary_json::text as summary_json, created_at, completed_at
                from shadow_ranking_runs
                where id = ?
                """,
            this::mapRunWithoutItems,
            runId
        );
        return runs.stream().findFirst()
            .map(run -> new ShadowRankingRun(
                run.id(),
                run.profileId(),
                run.baselineDecisionLogId(),
                run.championRankingVersion(),
                run.challengerRankingVersion(),
                run.featureSnapshotRunId(),
                run.rankingContext(),
                run.status(),
                run.summary(),
                run.createdAt(),
                run.completedAt(),
                findItems(run.id())
            ));
    }

    public List<ShadowRankingItem> findItems(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, shadow_run_id, candidate_profile_id, champion_position, challenger_position,
                    champion_score, challenger_score, position_delta, score_delta,
                    reason_delta_json::text as reason_delta_json, created_at
                from shadow_ranking_items
                where shadow_run_id = ?
                order by coalesce(challenger_position, champion_position), candidate_profile_id
                """,
            this::mapItem,
            runId
        );
    }

    private ShadowRankingRun mapRunWithoutItems(ResultSet rs, int rowNum) throws SQLException {
        return new ShadowRankingRun(
            rs.getObject("id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("baseline_decision_log_id", UUID.class),
            rs.getString("champion_ranking_version"),
            rs.getString("challenger_ranking_version"),
            rs.getObject("feature_snapshot_run_id", UUID.class),
            map(rs.getString("ranking_context_json")),
            rs.getString("status"),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            List.of()
        );
    }

    private ShadowRankingItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new ShadowRankingItem(
            rs.getObject("id", UUID.class),
            rs.getObject("shadow_run_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            integerOrNull(rs, "champion_position"),
            integerOrNull(rs, "challenger_position"),
            rs.getBigDecimal("champion_score"),
            rs.getBigDecimal("challenger_score"),
            integerOrNull(rs, "position_delta"),
            rs.getBigDecimal("score_delta"),
            map(rs.getString("reason_delta_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored shadow json is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("shadow value must be JSON serializable", exception);
        }
    }
}
