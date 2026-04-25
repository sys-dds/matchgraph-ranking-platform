package com.matchgraph.api.streaming;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.streaming.StreamingModels.CandidateTrendRun;
import com.matchgraph.api.streaming.StreamingModels.CandidateTrendScore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CandidateTrendRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CandidateTrendRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<UUID> candidatesWithWindows() {
        return jdbcTemplate.queryForList(
            "select distinct candidate_profile_id from streaming_candidate_feature_windows order by candidate_profile_id",
            UUID.class
        );
    }

    public Map<String, Object> latestWindow(UUID candidateId, String windowKey) {
        return jdbcTemplate.query(
            """
                select views, likes, passes, blocks, reports, match_creations
                from streaming_candidate_feature_windows
                where candidate_profile_id = ? and window_key = ?
                order by created_at desc limit 1
                """,
            rs -> rs.next()
                ? Map.of(
                    "views", rs.getLong("views"),
                    "likes", rs.getLong("likes"),
                    "passes", rs.getLong("passes"),
                    "blocks", rs.getLong("blocks"),
                    "reports", rs.getLong("reports"),
                    "matches", rs.getLong("match_creations")
                )
                : Map.of("views", 0L, "likes", 0L, "passes", 0L, "blocks", 0L, "reports", 0L, "matches", 0L),
            candidateId,
            windowKey
        );
    }

    public CandidateTrendRun createRun(List<CandidateTrendScore> scores, Map<String, Object> summary) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into candidate_trend_runs (id, status, summary_json) values (?, 'COMPLETED', ?::jsonb)",
            runId,
            json(summary)
        );
        for (CandidateTrendScore score : scores) {
            jdbcTemplate.update(
                """
                    insert into candidate_trend_scores (
                        id, run_id, candidate_profile_id, hotness_score, trend_direction, velocity_score,
                        safety_negative_score, bounded_boost, boost_allowed, boost_blocked_reason, explanation_json
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                score.id(),
                runId,
                score.candidateProfileId(),
                score.hotnessScore(),
                score.trendDirection(),
                score.velocityScore(),
                score.safetyNegativeScore(),
                score.boundedBoost(),
                score.boostAllowed(),
                score.boostBlockedReason(),
                json(score.explanation())
            );
            if (!score.boostAllowed() || score.boundedBoost().signum() > 0) {
                jdbcTemplate.update(
                    "insert into candidate_trend_events (id, candidate_profile_id, event_type, trend_score_id, detail_json) values (?, ?, ?, ?, ?::jsonb)",
                    UUID.randomUUID(),
                    score.candidateProfileId(),
                    score.boostAllowed() ? "TREND_BOOST_ALLOWED" : "TREND_BOOST_BLOCKED",
                    score.id(),
                    json(score.explanation())
                );
            }
        }
        return new CandidateTrendRun(runId, "COMPLETED", summary, scores);
    }

    public CandidateTrendRun run(UUID runId) {
        Map<String, Object> summary = jdbcTemplate.queryForObject(
            "select summary_json from candidate_trend_runs where id = ?",
            (rs, rowNum) -> readMap(rs.getString("summary_json")),
            runId
        );
        return new CandidateTrendRun(runId, "COMPLETED", summary, scoresForRun(runId));
    }

    public List<CandidateTrendScore> scoresForRun(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, candidate_profile_id, trend_direction, velocity_score, hotness_score, safety_negative_score,
                       bounded_boost, boost_allowed, boost_blocked_reason, explanation_json
                from candidate_trend_scores where run_id = ? order by hotness_score desc
                """,
            this::score,
            runId
        );
    }

    public CandidateTrendScore latest(UUID candidateId) {
        return jdbcTemplate.queryForObject(
            """
                select id, candidate_profile_id, trend_direction, velocity_score, hotness_score, safety_negative_score,
                       bounded_boost, boost_allowed, boost_blocked_reason, explanation_json
                from candidate_trend_scores
                where candidate_profile_id = ?
                order by created_at desc limit 1
                """,
            this::score,
            candidateId
        );
    }

    private CandidateTrendScore score(ResultSet rs, int rowNum) throws SQLException {
        return new CandidateTrendScore(
            rs.getObject("id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getString("trend_direction"),
            rs.getBigDecimal("velocity_score"),
            rs.getBigDecimal("hotness_score"),
            rs.getBigDecimal("safety_negative_score"),
            rs.getBigDecimal("bounded_boost"),
            rs.getBoolean("boost_allowed"),
            rs.getString("boost_blocked_reason"),
            readMap(rs.getString("explanation_json"))
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize trend JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to read trend JSON", exception);
        }
    }
}
