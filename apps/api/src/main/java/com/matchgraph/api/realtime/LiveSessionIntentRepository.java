package com.matchgraph.api.realtime;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.realtime.RealtimeModels.LiveSessionIntentSnapshot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LiveSessionIntentRepository {

    private static final TypeReference<Map<String, BigDecimal>> BIG_DECIMAL_MAP = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LiveSessionIntentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID profileForSession(UUID sessionId) {
        return jdbcTemplate.queryForObject(
            "select profile_id from recommendation_sessions where id = ?",
            UUID.class,
            sessionId
        );
    }

    public List<Map<String, Object>> realtimeEvents(UUID sessionId) {
        return jdbcTemplate.query(
            """
                select profile_id, event_type, source_key, occurred_at
                from realtime_interaction_events
                where session_id = ?
                order by occurred_at
                """,
            (rs, rowNum) -> Map.of(
                "profileId", rs.getObject("profile_id", UUID.class),
                "eventType", rs.getString("event_type"),
                "sourceKey", rs.getString("source_key") == null ? "" : rs.getString("source_key"),
                "occurredAt", rs.getObject("occurred_at", OffsetDateTime.class)
            ),
            sessionId
        );
    }

    public LiveSessionIntentSnapshot save(UUID sessionId, UUID profileId, Map<String, BigDecimal> sourceWeights, Map<String, BigDecimal> positiveWeights, Map<String, BigDecimal> negativeWeights, BigDecimal confidence, BigDecimal decayFactor, Map<String, Object> explanation, OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into live_session_intent_snapshots (
                    id, session_id, profile_id, source_weights_json, positive_weights_json,
                    negative_weights_json, confidence_score, decay_factor, explanation_json, expires_at
                )
                values (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?)
                """,
            id,
            sessionId,
            profileId,
            toJson(sourceWeights),
            toJson(positiveWeights),
            toJson(negativeWeights),
            confidence,
            decayFactor,
            toJson(explanation),
            expiresAt
        );
        jdbcTemplate.update(
            """
                insert into live_session_intent_decay_runs (id, session_id, profile_id, decay_factor, summary_json)
                values (?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            sessionId,
            profileId,
            decayFactor,
            toJson(Map.of("confidenceScore", confidence, "events", explanation.getOrDefault("eventCount", 0)))
        );
        return latest(sessionId).orElseThrow();
    }

    public Optional<LiveSessionIntentSnapshot> latest(UUID sessionId) {
        return jdbcTemplate.query(
            """
                select id, session_id, profile_id, source_weights_json::text as source_weights_json,
                    positive_weights_json::text as positive_weights_json,
                    negative_weights_json::text as negative_weights_json,
                    confidence_score, decay_factor, explanation_json::text as explanation_json, expires_at
                from live_session_intent_snapshots
                where session_id = ?
                order by created_at desc
                limit 1
                """,
            (rs, rowNum) -> new LiveSessionIntentSnapshot(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("profile_id", UUID.class),
                bigDecimalMap(rs.getString("source_weights_json")),
                bigDecimalMap(rs.getString("positive_weights_json")),
                bigDecimalMap(rs.getString("negative_weights_json")),
                rs.getBigDecimal("confidence_score"),
                rs.getBigDecimal("decay_factor"),
                objectMap(rs.getString("explanation_json")),
                rs.getObject("expires_at", OffsetDateTime.class)
            ),
            sessionId
        ).stream().findFirst();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("live intent value must be JSON serializable", exception);
        }
    }

    private Map<String, BigDecimal> bigDecimalMap(String json) {
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json == null ? "{}" : json, BIG_DECIMAL_MAP));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored live intent weights are invalid", exception);
        }
    }

    private Map<String, Object> objectMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored live intent explanation is invalid", exception);
        }
    }
}
