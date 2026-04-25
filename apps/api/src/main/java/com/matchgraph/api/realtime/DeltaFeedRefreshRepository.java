package com.matchgraph.api.realtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.realtime.RealtimeModels.DeltaFeedRefreshItem;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeltaFeedRefreshRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DeltaFeedRefreshRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createRun(UUID profileId, UUID feedSnapshotId, UUID servingRequestId, UUID sessionId, UUID triggerEventId, int removed, int created, int moved, int unchanged, boolean degraded, UUID traceId, Map<String, Object> reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into delta_feed_refresh_runs (
                    id, profile_id, feed_snapshot_id, serving_request_id, session_id, trigger_event_id,
                    status, removed_count, new_count, moved_count, unchanged_count, degraded, trace_id, reason_json
                )
                values (?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            profileId,
            feedSnapshotId,
            servingRequestId,
            sessionId,
            triggerEventId,
            removed,
            created,
            moved,
            unchanged,
            degraded,
            traceId,
            toJson(reason)
        );
        return id;
    }

    public void insertItem(UUID runId, DeltaFeedRefreshItem item) {
        jdbcTemplate.update(
            """
                insert into delta_feed_refresh_items (
                    id, run_id, candidate_profile_id, item_action, old_position, new_position, reason_json
                )
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            item.candidateProfileId(),
            item.action(),
            item.oldPosition(),
            item.newPosition(),
            toJson(item.reason())
        );
    }

    public List<UUID> invalidatedCandidates(UUID profileId) {
        return jdbcTemplate.queryForList(
            """
                select candidate_profile_id
                from realtime_candidate_invalidations
                where profile_id = ?
                  and candidate_profile_id is not null
                  and (hard_invalidation = true or expires_at is null or expires_at > now())
                """,
            UUID.class,
            profileId
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("delta refresh value must be JSON serializable", exception);
        }
    }
}
