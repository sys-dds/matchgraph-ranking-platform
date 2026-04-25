package com.matchgraph.api.interleaving;

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
public class InterleavingRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InterleavingRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createExperiment(InterleavingExperimentRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into interleaving_experiments (
                    id, experiment_key, name, status, ranker_a_version, ranker_b_version, method, config_json
                )
                values (?, ?, ?, ?, ?, ?, 'TEAM_DRAFT', ?::jsonb)
                """,
            id,
            request.experimentKey().trim(),
            request.name().trim(),
            request.status() == null || request.status().isBlank() ? "ACTIVE" : request.status().trim(),
            request.rankerAVersion(),
            request.rankerBVersion(),
            toJson(request.config() == null ? Map.of() : request.config())
        );
        return id;
    }

    public Optional<InterleavingExperiment> findExperiment(String experimentKey) {
        return jdbcTemplate.query(
            """
                select id, experiment_key, name, status, ranker_a_version, ranker_b_version,
                    method, config_json::text as config_json, created_at, updated_at
                from interleaving_experiments
                where experiment_key = ?
                """,
            this::mapExperiment,
            experimentKey
        ).stream().findFirst();
    }

    public UUID createSession(InterleavingExperiment experiment, UUID profileId, UUID featureSnapshotRunId, Map<String, Object> context) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into interleaving_sessions (
                    id, experiment_id, profile_id, feature_snapshot_run_id, ranker_a_version,
                    ranker_b_version, method, context_json, status
                )
                values (?, ?, ?, ?, ?, ?, 'TEAM_DRAFT', ?::jsonb, 'RUNNING')
                """,
            id,
            experiment.id(),
            profileId,
            featureSnapshotRunId,
            experiment.rankerAVersion(),
            experiment.rankerBVersion(),
            toJson(context == null ? Map.of() : context)
        );
        return id;
    }

    public void completeSession(UUID sessionId, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update interleaving_sessions
                set status = 'COMPLETED',
                    summary_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            toJson(summary),
            sessionId
        );
    }

    public void insertItem(InterleavingItem item) {
        jdbcTemplate.update(
            """
                insert into interleaving_items (
                    id, session_id, candidate_profile_id, position, attributed_ranker,
                    ranker_a_position, ranker_b_position, score_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            item.id(),
            item.sessionId(),
            item.candidateProfileId(),
            item.position(),
            item.attributedRanker(),
            item.rankerAPosition(),
            item.rankerBPosition(),
            toJson(item.score())
        );
    }

    public Optional<InterleavingSession> findSession(UUID sessionId) {
        return jdbcTemplate.query(
            """
                select id, experiment_id, profile_id, feature_snapshot_run_id, ranker_a_version,
                    ranker_b_version, method, context_json::text as context_json, status,
                    summary_json::text as summary_json, created_at, completed_at
                from interleaving_sessions
                where id = ?
                """,
            this::mapSessionWithoutItems,
            sessionId
        ).stream().findFirst()
            .map(session -> new InterleavingSession(
                session.id(),
                session.experimentId(),
                session.profileId(),
                session.featureSnapshotRunId(),
                session.rankerAVersion(),
                session.rankerBVersion(),
                session.method(),
                session.context(),
                session.status(),
                session.summary(),
                session.createdAt(),
                session.completedAt(),
                findItems(session.id())
            ));
    }

    public List<InterleavingItem> findItems(UUID sessionId) {
        return jdbcTemplate.query(
            """
                select id, session_id, candidate_profile_id, position, attributed_ranker,
                    ranker_a_position, ranker_b_position, score_json::text as score_json, created_at
                from interleaving_items
                where session_id = ?
                order by position
                """,
            this::mapItem,
            sessionId
        );
    }

    public Optional<InterleavingItem> findItem(UUID sessionId, UUID candidateProfileId) {
        return findItems(sessionId).stream()
            .filter(item -> item.candidateProfileId().equals(candidateProfileId))
            .findFirst();
    }

    public UUID insertOutcome(
        UUID sessionId,
        InterleavingItem item,
        UUID interactionEventId,
        String outcomeEventType,
        BigDecimal rewardValue,
        String winner,
        Map<String, Object> summary
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into interleaving_outcomes (
                    id, session_id, interleaving_item_id, candidate_profile_id, interaction_event_id,
                    outcome_event_type, attributed_ranker, reward_value, winner, summary_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            sessionId,
            item == null ? null : item.id(),
            item == null ? null : item.candidateProfileId(),
            interactionEventId,
            outcomeEventType,
            item == null ? null : item.attributedRanker(),
            rewardValue,
            winner,
            toJson(summary)
        );
        return id;
    }

    public List<InterleavingOutcome> outcomes(UUID sessionId) {
        return jdbcTemplate.query(
            """
                select id, session_id, interleaving_item_id, candidate_profile_id, interaction_event_id,
                    outcome_event_type, attributed_ranker, reward_value, winner,
                    summary_json::text as summary_json, created_at
                from interleaving_outcomes
                where session_id = ?
                order by created_at
                """,
            this::mapOutcome,
            sessionId
        );
    }

    private InterleavingExperiment mapExperiment(ResultSet rs, int rowNum) throws SQLException {
        return new InterleavingExperiment(
            rs.getObject("id", UUID.class),
            rs.getString("experiment_key"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getString("ranker_a_version"),
            rs.getString("ranker_b_version"),
            rs.getString("method"),
            map(rs.getString("config_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private InterleavingSession mapSessionWithoutItems(ResultSet rs, int rowNum) throws SQLException {
        return new InterleavingSession(
            rs.getObject("id", UUID.class),
            rs.getObject("experiment_id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("feature_snapshot_run_id", UUID.class),
            rs.getString("ranker_a_version"),
            rs.getString("ranker_b_version"),
            rs.getString("method"),
            map(rs.getString("context_json")),
            rs.getString("status"),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            List.of()
        );
    }

    private InterleavingItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new InterleavingItem(
            rs.getObject("id", UUID.class),
            rs.getObject("session_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getInt("position"),
            rs.getString("attributed_ranker"),
            integerOrNull(rs, "ranker_a_position"),
            integerOrNull(rs, "ranker_b_position"),
            map(rs.getString("score_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private InterleavingOutcome mapOutcome(ResultSet rs, int rowNum) throws SQLException {
        return new InterleavingOutcome(
            rs.getObject("id", UUID.class),
            rs.getObject("session_id", UUID.class),
            rs.getObject("interleaving_item_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getObject("interaction_event_id", UUID.class),
            rs.getString("outcome_event_type"),
            rs.getString("attributed_ranker"),
            rs.getBigDecimal("reward_value"),
            rs.getString("winner"),
            map(rs.getString("summary_json")),
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
            throw new IllegalStateException("stored interleaving json is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("interleaving value must be JSON serializable", exception);
        }
    }
}
