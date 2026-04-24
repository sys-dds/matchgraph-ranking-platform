package com.matchgraph.api.interaction;

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
public class InteractionRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InteractionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<InteractionResponse> findByClientEventId(UUID actorProfileId, String clientEventId) {
        List<InteractionResponse> interactions = jdbcTemplate.query(
            """
                select id, client_event_id, actor_profile_id, target_profile_id, event_type, occurred_at,
                    request_id, retrieval_run_id, candidate_source, ranking_version, experiment_id, variant,
                    feed_position, metadata_json::text as metadata_json, created_at
                from interaction_events
                where actor_profile_id = ?
                  and client_event_id = ?
                """,
            this::mapInteraction,
            actorProfileId,
            clientEventId
        );
        return interactions.stream().findFirst();
    }

    public InteractionResponse create(UUID id, UUID actorProfileId, RecordInteractionRequest request) {
        return jdbcTemplate.queryForObject(
            """
                insert into interaction_events (
                    id, client_event_id, actor_profile_id, target_profile_id, event_type, occurred_at,
                    request_id, retrieval_run_id, candidate_source, ranking_version, experiment_id, variant,
                    feed_position, metadata_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                returning id, client_event_id, actor_profile_id, target_profile_id, event_type, occurred_at,
                    request_id, retrieval_run_id, candidate_source, ranking_version, experiment_id, variant,
                    feed_position, metadata_json::text as metadata_json, created_at
                """,
            this::mapInteraction,
            id,
            request.clientEventId().trim(),
            actorProfileId,
            request.targetProfileId(),
            request.eventType(),
            request.occurredAt(),
            trimToNull(request.requestId()),
            request.retrievalRunId(),
            trimToNull(request.candidateSource()),
            trimToNull(request.rankingVersion()),
            trimToNull(request.experimentId()),
            trimToNull(request.variant()),
            request.feedPosition(),
            toJson(request.metadata())
        );
    }

    public List<InteractionResponse> recent(UUID actorProfileId, int limit) {
        return jdbcTemplate.query(
            """
                select id, client_event_id, actor_profile_id, target_profile_id, event_type, occurred_at,
                    request_id, retrieval_run_id, candidate_source, ranking_version, experiment_id, variant,
                    feed_position, metadata_json::text as metadata_json, created_at
                from interaction_events
                where actor_profile_id = ?
                order by occurred_at desc, created_at desc
                limit ?
                """,
            this::mapInteraction,
            actorProfileId,
            limit
        );
    }

    private InteractionResponse mapInteraction(ResultSet rs, int rowNum) throws SQLException {
        return new InteractionResponse(
            rs.getObject("id", UUID.class),
            rs.getString("client_event_id"),
            rs.getObject("actor_profile_id", UUID.class),
            rs.getObject("target_profile_id", UUID.class),
            rs.getString("event_type"),
            rs.getObject("occurred_at", OffsetDateTime.class),
            rs.getString("request_id"),
            rs.getObject("retrieval_run_id", UUID.class),
            rs.getString("candidate_source"),
            rs.getString("ranking_version"),
            rs.getString("experiment_id"),
            rs.getString("variant"),
            (Integer) rs.getObject("feed_position"),
            fromJson(rs.getString("metadata_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("metadata must be JSON serializable", exception);
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored metadata_json is invalid", exception);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
