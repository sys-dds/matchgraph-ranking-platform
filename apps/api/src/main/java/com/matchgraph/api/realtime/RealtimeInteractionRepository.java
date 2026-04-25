package com.matchgraph.api.realtime;

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
import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionEvent;
import com.matchgraph.api.realtime.RealtimeModels.CandidateInvalidation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RealtimeInteractionRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RealtimeInteractionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<RealtimeInteractionEvent> findByEventKey(String eventKey) {
        return jdbcTemplate.query(
            """
                select id, event_key, profile_id, candidate_profile_id, feed_snapshot_id, feed_item_id,
                    serving_request_id, session_id, event_type, source_key, occurred_at, received_at,
                    metadata_json::text as metadata_json, processing_status, processed_at
                from realtime_interaction_events
                where event_key = ?
                """,
            this::mapEvent,
            eventKey
        ).stream().findFirst();
    }

    public RealtimeInteractionEvent insert(
        UUID id,
        String eventKey,
        UUID profileId,
        UUID candidateProfileId,
        UUID feedSnapshotId,
        UUID feedItemId,
        UUID servingRequestId,
        UUID sessionId,
        String eventType,
        String sourceKey,
        OffsetDateTime occurredAt,
        Map<String, Object> metadata
    ) {
        jdbcTemplate.update(
            """
                insert into realtime_interaction_events (
                    id, event_key, profile_id, candidate_profile_id, feed_snapshot_id, feed_item_id,
                    serving_request_id, session_id, event_type, source_key, occurred_at, metadata_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            eventKey,
            profileId,
            candidateProfileId,
            feedSnapshotId,
            feedItemId,
            servingRequestId,
            sessionId,
            eventType,
            sourceKey,
            occurredAt,
            toJson(metadata == null ? Map.of() : metadata)
        );
        jdbcTemplate.update(
            """
                insert into realtime_interaction_dedupe (event_key, event_id)
                values (?, ?)
                on conflict (event_key) do update
                set duplicate_count = realtime_interaction_dedupe.duplicate_count + 1
                """,
            eventKey,
            id
        );
        return findByEventKey(eventKey).orElseThrow();
    }

    public void incrementDuplicate(String eventKey) {
        jdbcTemplate.update(
            "update realtime_interaction_dedupe set duplicate_count = duplicate_count + 1 where event_key = ?",
            eventKey
        );
    }

    public void markProcessed(UUID eventId) {
        jdbcTemplate.update(
            "update realtime_interaction_events set processing_status = 'PROCESSED', processed_at = now() where id = ?",
            eventId
        );
    }

    public UUID insertInvalidation(UUID profileId, UUID candidateProfileId, UUID eventId, String reason, boolean hard, List<String> targets, Map<String, Object> detail) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into realtime_candidate_invalidations (
                    id, profile_id, candidate_profile_id, event_id, reason, hard_invalidation, expires_at, detail_json
                )
                values (?, ?, ?, ?, ?, ?, case when ? then null else now() + interval '2 hours' end, ?::jsonb)
                """,
            id,
            profileId,
            candidateProfileId,
            eventId,
            reason,
            hard,
            hard,
            toJson(detail == null ? Map.of() : detail)
        );
        for (String target : targets) {
            jdbcTemplate.update(
                "insert into candidate_invalidation_targets (id, invalidation_id, target_key) values (?, ?, ?)",
                UUID.randomUUID(),
                id,
                target
            );
        }
        return id;
    }

    public UUID createInvalidation(UUID profileId, UUID candidateProfileId, UUID eventId, String reason, boolean hard, Integer ttlMinutes, Map<String, Object> detail) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into realtime_candidate_invalidations (
                    id, profile_id, candidate_profile_id, event_id, reason, hard_invalidation, expires_at, detail_json
                )
                values (?, ?, ?, ?, ?, ?, case when ? then null else now() + (? || ' minutes')::interval end, ?::jsonb)
                """,
            id,
            profileId,
            candidateProfileId,
            eventId,
            reason,
            hard,
            hard,
            ttlMinutes == null ? 60 : ttlMinutes,
            toJson(detail == null ? Map.of() : detail)
        );
        List<String> targets = hard
            ? List.of("CURRENT_FEED", "CACHE", "CANDIDATE_POOL", "PRE_RANK", "SLATE", "DELTA_REFRESH", "FUTURE_SESSION")
            : List.of("CURRENT_FEED", "PRE_RANK", "SLATE", "DELTA_REFRESH", "FUTURE_SESSION");
        for (String target : targets) {
            jdbcTemplate.update(
                "insert into candidate_invalidation_targets (id, invalidation_id, target_key) values (?, ?, ?)",
                UUID.randomUUID(),
                id,
                target
            );
        }
        return id;
    }

    public List<CandidateInvalidation> invalidations(UUID profileId) {
        return jdbcTemplate.query(
            """
                select id, profile_id, candidate_profile_id, event_id, reason, hard_invalidation,
                    expires_at, detail_json::text as detail_json
                from realtime_candidate_invalidations
                where profile_id = ?
                order by created_at desc
                """,
            (rs, rowNum) -> new CandidateInvalidation(
                rs.getObject("id", UUID.class),
                rs.getObject("profile_id", UUID.class),
                rs.getObject("candidate_profile_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("reason"),
                rs.getBoolean("hard_invalidation"),
                rs.getObject("expires_at", OffsetDateTime.class),
                map(rs.getString("detail_json")),
                invalidationTargets(rs.getObject("id", UUID.class))
            ),
            profileId
        );
    }

    public boolean invalidated(UUID profileId, UUID candidateId) {
        Integer count = jdbcTemplate.queryForObject(
            """
                select count(*)
                from realtime_candidate_invalidations
                where profile_id = ?
                  and candidate_profile_id = ?
                  and (hard_invalidation = true or expires_at is null or expires_at > now())
                """,
            Integer.class,
            profileId,
            candidateId
        );
        return count != null && count > 0;
    }

    public void insertSourceSignal(UUID profileId, UUID sessionId, String sourceKey, String signalType, java.math.BigDecimal value, Map<String, Object> detail) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return;
        }
        jdbcTemplate.update(
            """
                insert into source_feedback_signals (
                    id, profile_id, session_id, source_key, signal_type, signal_value, quality_score
                )
                values (?, ?, ?, ?, ?, ?, ?)
                """,
            UUID.randomUUID(),
            profileId,
            sessionId,
            sourceKey,
            signalType,
            value,
            value.signum() >= 0 ? java.math.BigDecimal.ONE : java.math.BigDecimal.ZERO
        );
    }

    public void insertSourceBudgetSnapshot(UUID profileId, UUID sessionId, String sourceKey, int before, int after, Map<String, Object> reason) {
        jdbcTemplate.update(
            """
                insert into adaptive_source_budget_snapshots (
                    id, profile_id, session_id, source_key, budget_before, budget_after, reason_json
                )
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            profileId,
            sessionId,
            sourceKey,
            before,
            after,
            toJson(reason == null ? Map.of() : reason)
        );
    }

    public java.math.BigDecimal recentSourceSignal(UUID profileId, UUID sessionId, String sourceKey) {
        if (sessionId == null) {
            java.math.BigDecimal value = jdbcTemplate.queryForObject(
                """
                    select coalesce(sum(signal_value), 0)
                    from source_feedback_signals
                    where profile_id = ?
                      and source_key = ?
                      and created_at >= now() - interval '1 hour'
                    """,
                java.math.BigDecimal.class,
                profileId,
                sourceKey
            );
            return value == null ? java.math.BigDecimal.ZERO : value;
        }
        java.math.BigDecimal value = jdbcTemplate.queryForObject(
            """
                select coalesce(sum(signal_value), 0)
                from source_feedback_signals
                where profile_id = ?
                  and session_id = ?
                  and source_key = ?
                  and created_at >= now() - interval '1 hour'
                """,
            java.math.BigDecimal.class,
            profileId,
            sessionId,
            sourceKey
        );
        return value == null ? java.math.BigDecimal.ZERO : value;
    }

    public List<RealtimeInteractionEvent> listByProfile(UUID profileId) {
        return jdbcTemplate.query(
            """
                select id, event_key, profile_id, candidate_profile_id, feed_snapshot_id, feed_item_id,
                    serving_request_id, session_id, event_type, source_key, occurred_at, received_at,
                    metadata_json::text as metadata_json, processing_status, processed_at
                from realtime_interaction_events
                where profile_id = ?
                order by occurred_at desc, received_at desc
                limit 100
                """,
            this::mapEvent,
            profileId
        );
    }

    public Optional<RealtimeInteractionEvent> find(UUID eventId) {
        return jdbcTemplate.query(
            """
                select id, event_key, profile_id, candidate_profile_id, feed_snapshot_id, feed_item_id,
                    serving_request_id, session_id, event_type, source_key, occurred_at, received_at,
                    metadata_json::text as metadata_json, processing_status, processed_at
                from realtime_interaction_events
                where id = ?
                """,
            this::mapEvent,
            eventId
        ).stream().findFirst();
    }

    public UUID createMaterializationRun(UUID profileId, UUID candidateId, Map<String, Object> summary) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into nearline_feature_materialization_runs (
                    id, profile_id, candidate_profile_id, status, summary_json, completed_at
                )
                values (?, ?, ?, 'COMPLETED', ?::jsonb, now())
                """,
            id,
            profileId,
            candidateId,
            toJson(summary == null ? Map.of() : summary)
        );
        return id;
    }

    public java.math.BigDecimal eventCount(UUID profileId, UUID candidateId, String eventType, String window) {
        String interval = switch (window) {
            case "1m" -> "1 minute";
            case "5m" -> "5 minutes";
            case "24h" -> "24 hours";
            default -> "1 hour";
        };
        if (candidateId == null) {
            Integer count = jdbcTemplate.queryForObject(
                """
                    select count(*)
                    from realtime_interaction_events
                    where profile_id = ?
                      and event_type = ?
                      and occurred_at >= now() - (? || '')::interval
                    """,
                Integer.class,
                profileId,
                eventType,
                interval
            );
            return java.math.BigDecimal.valueOf(count == null ? 0 : count);
        }
        Integer count = jdbcTemplate.queryForObject(
            """
                select count(*)
                from realtime_interaction_events
                where profile_id = ?
                  and candidate_profile_id = ?
                  and event_type = ?
                  and occurred_at >= now() - (? || '')::interval
                """,
            Integer.class,
            profileId,
            candidateId,
            eventType,
            interval
        );
        return java.math.BigDecimal.valueOf(count == null ? 0 : count);
    }

    public void upsertProfileFeature(UUID runId, UUID profileId, String key, java.math.BigDecimal numeric, Map<String, Object> json, String status) {
        jdbcTemplate.update(
            """
                insert into nearline_profile_features (id, run_id, profile_id, feature_key, numeric_value, json_value, freshness_status)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                on conflict (profile_id, feature_key) do update
                set run_id = excluded.run_id, numeric_value = excluded.numeric_value, json_value = excluded.json_value,
                    last_materialized_at = now(), freshness_status = excluded.freshness_status
                """,
            UUID.randomUUID(),
            runId,
            profileId,
            key,
            numeric,
            json == null ? null : toJson(json),
            status
        );
    }

    public void upsertCandidateFeature(UUID runId, UUID candidateId, String key, java.math.BigDecimal numeric, Map<String, Object> json, String status) {
        jdbcTemplate.update(
            """
                insert into nearline_candidate_features (id, run_id, candidate_profile_id, feature_key, numeric_value, json_value, freshness_status)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                on conflict (candidate_profile_id, feature_key) do update
                set run_id = excluded.run_id, numeric_value = excluded.numeric_value, json_value = excluded.json_value,
                    last_materialized_at = now(), freshness_status = excluded.freshness_status
                """,
            UUID.randomUUID(),
            runId,
            candidateId,
            key,
            numeric,
            json == null ? null : toJson(json),
            status
        );
    }

    public void upsertPairFeature(UUID runId, UUID profileId, UUID candidateId, String key, java.math.BigDecimal numeric, Map<String, Object> json, String status) {
        jdbcTemplate.update(
            """
                insert into nearline_pair_features (id, run_id, profile_id, candidate_profile_id, feature_key, numeric_value, json_value, last_interaction_at, freshness_status)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, now(), ?)
                on conflict (profile_id, candidate_profile_id, feature_key) do update
                set run_id = excluded.run_id, numeric_value = excluded.numeric_value, json_value = excluded.json_value,
                    last_interaction_at = excluded.last_interaction_at, last_materialized_at = now(), freshness_status = excluded.freshness_status
                """,
            UUID.randomUUID(),
            runId,
            profileId,
            candidateId,
            key,
            numeric,
            json == null ? null : toJson(json),
            status
        );
    }

    public Map<String, Object> profileFeatures(UUID profileId) {
        return featureMap(
            "select feature_key, numeric_value, json_value::text as json_value, freshness_status from nearline_profile_features where profile_id = ?",
            profileId
        );
    }

    public Map<String, Object> candidateFeatures(UUID candidateId) {
        return featureMap(
            "select feature_key, numeric_value, json_value::text as json_value, freshness_status from nearline_candidate_features where candidate_profile_id = ?",
            candidateId
        );
    }

    public Map<String, Object> pairFeatures(UUID profileId, UUID candidateId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            "select feature_key, numeric_value, json_value::text as json_value, freshness_status from nearline_pair_features where profile_id = ? and candidate_profile_id = ?",
            (rs, rowNum) -> Map.of(
                "featureKey", rs.getString("feature_key"),
                "value", rs.getBigDecimal("numeric_value") == null ? map(rs.getString("json_value")) : rs.getBigDecimal("numeric_value"),
                "freshnessStatus", rs.getString("freshness_status")
            ),
            profileId,
            candidateId
        );
        return rows.stream().collect(java.util.stream.Collectors.toMap(row -> String.valueOf(row.get("featureKey")), row -> row.get("value"), (a, b) -> b, java.util.LinkedHashMap::new));
    }

    public Map<String, Object> sourcePreference(UUID profileId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            """
                select coalesce(source_key, 'UNKNOWN') as source_key,
                    sum(case
                        when event_type in ('LIKE', 'SOURCE_POSITIVE', 'MATCH_CREATED') then 1
                        when event_type = 'PROFILE_VIEW' then 0.25
                        when event_type in ('PASS', 'SOURCE_NEGATIVE', 'BLOCK', 'REPORT') then -1
                        else 0
                    end) as preference
                from realtime_interaction_events
                where profile_id = ?
                  and received_at >= now() - interval '24 hours'
                group by coalesce(source_key, 'UNKNOWN')
                """,
            (rs, rowNum) -> Map.of("sourceKey", rs.getString("source_key"), "preference", rs.getBigDecimal("preference")),
            profileId
        );
        return rows.stream().collect(java.util.stream.Collectors.toMap(row -> String.valueOf(row.get("sourceKey")), row -> row.get("preference"), (a, b) -> b, java.util.LinkedHashMap::new));
    }

    private RealtimeInteractionEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new RealtimeInteractionEvent(
            rs.getObject("id", UUID.class),
            rs.getString("event_key"),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getObject("feed_snapshot_id", UUID.class),
            rs.getObject("feed_item_id", UUID.class),
            rs.getObject("serving_request_id", UUID.class),
            rs.getObject("session_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("source_key"),
            rs.getObject("occurred_at", OffsetDateTime.class),
            rs.getObject("received_at", OffsetDateTime.class),
            map(rs.getString("metadata_json")),
            rs.getString("processing_status"),
            rs.getObject("processed_at", OffsetDateTime.class)
        );
    }

    private List<String> invalidationTargets(UUID invalidationId) {
        return jdbcTemplate.queryForList(
            "select target_key from candidate_invalidation_targets where invalidation_id = ? order by target_key",
            String.class,
            invalidationId
        );
    }

    private Map<String, Object> featureMap(String sql, UUID id) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> Map.of(
                "featureKey", rs.getString("feature_key"),
                "value", rs.getBigDecimal("numeric_value") == null ? map(rs.getString("json_value")) : rs.getBigDecimal("numeric_value"),
                "freshnessStatus", rs.getString("freshness_status")
            ),
            id
        );
        return rows.stream().collect(java.util.stream.Collectors.toMap(row -> String.valueOf(row.get("featureKey")), row -> row.get("value"), (a, b) -> b, java.util.LinkedHashMap::new));
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("realtime value must be JSON serializable", exception);
        }
    }

    Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored realtime JSON is invalid", exception);
        }
    }
}
