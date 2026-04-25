package com.matchgraph.api.streaming;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.streaming.StreamingModels.CandidateFeatureWindow;
import com.matchgraph.api.streaming.StreamingModels.FeatureWindowRun;
import com.matchgraph.api.streaming.StreamingModels.ProfileFeatureWindow;
import com.matchgraph.api.streaming.StreamingModels.SourceFeatureWindow;
import com.matchgraph.api.streaming.StreamingModels.SurfaceFeatureWindow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StreamingFeatureWindowRepository {

    private static final List<String> WINDOWS = List.of("1m", "5m", "1h", "24h");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public StreamingFeatureWindowRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<String> windowKeys() {
        return WINDOWS;
    }

    public FeatureWindowRun createRun(boolean approximate, Map<String, Object> summary) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into streaming_feature_window_runs (id, status, window_keys_json, approximate, summary_json) values (?, 'COMPLETED', ?::jsonb, ?, ?::jsonb)",
            id,
            json(WINDOWS),
            approximate,
            json(summary)
        );
        return new FeatureWindowRun(id, "COMPLETED", approximate, summary);
    }

    public void insertProfile(UUID runId, ProfileFeatureWindow window) {
        jdbcTemplate.update(
            """
                insert into streaming_profile_feature_windows (
                    id, run_id, profile_id, window_key, impressions, views, likes, passes, blocks, reports,
                    match_creations, feed_dismisses, source_positive, source_negative, delta_refreshes,
                    approximate, detail_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            window.profileId(),
            window.windowKey(),
            0,
            window.views(),
            window.likes(),
            window.passes(),
            window.blocks(),
            window.reports(),
            window.matchCreations(),
            window.feedDismisses(),
            window.approximate(),
            json(Map.of("source", "durable realtime_interaction_events"))
        );
    }

    public void insertCandidate(UUID runId, CandidateFeatureWindow window) {
        jdbcTemplate.update(
            """
                insert into streaming_candidate_feature_windows (
                    id, run_id, candidate_profile_id, window_key, impressions, views, likes, passes, blocks, reports,
                    match_creations, feed_dismisses, source_positive, source_negative, delta_refreshes,
                    approximate, detail_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            window.candidateProfileId(),
            window.windowKey(),
            0,
            window.views(),
            window.likes(),
            window.passes(),
            window.blocks(),
            window.reports(),
            window.matchCreations(),
            window.approximate(),
            json(Map.of("safetyNegativeScore", window.safetyNegativeScore(), "source", "durable realtime_interaction_events"))
        );
    }

    public void insertSource(UUID runId, SourceFeatureWindow window) {
        jdbcTemplate.update(
            """
                insert into streaming_source_feature_windows (
                    id, run_id, source_key, window_key, returned_candidates, timeout_count, fallback_count,
                    empty_result_count, safety_filtered_count, latency_ms_avg, approximate, detail_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            window.sourceKey(),
            window.windowKey(),
            window.returnedCandidates(),
            window.timeoutCount(),
            window.fallbackCount(),
            window.emptyResultCount(),
            window.latencyMsAvg(),
            window.approximate(),
            json(Map.of("source", "source_call_results", "partialEvidence", true))
        );
    }

    public void insertSurface(UUID runId, SurfaceFeatureWindow window) {
        jdbcTemplate.update(
            """
                insert into streaming_surface_feature_windows (
                    id, run_id, surface_key, window_key, requests, degraded_responses, partial_responses,
                    served_count_avg, fallback_count, timeout_count, approximate, detail_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            window.surfaceKey(),
            window.windowKey(),
            window.requests(),
            window.degradedResponses(),
            window.partialResponses(),
            window.servedCountAvg(),
            window.fallbackCount(),
            window.approximate(),
            json(Map.of("source", "multi_stage_serving_requests and serving_quality_runs", "partialEvidence", true))
        );
    }

    public ProfileFeatureWindow profileWindow(UUID profileId, String windowKey) {
        return jdbcTemplate.queryForObject(
            """
                select profile_id, window_key, views, likes, passes, blocks, reports, feed_dismisses, match_creations, approximate
                from streaming_profile_feature_windows
                where profile_id = ? and window_key = ?
                order by created_at desc limit 1
                """,
            this::profile,
            profileId,
            windowKey
        );
    }

    public List<CandidateFeatureWindow> candidateWindows(UUID candidateId) {
        return jdbcTemplate.query(
            """
                select candidate_profile_id, window_key, views, likes, passes, blocks, reports, match_creations, approximate
                from streaming_candidate_feature_windows
                where candidate_profile_id = ?
                order by created_at desc
                """,
            this::candidate,
            candidateId
        );
    }

    public List<SourceFeatureWindow> sourceWindows(String sourceKey) {
        return jdbcTemplate.query(
            """
                select source_key, window_key, returned_candidates, timeout_count, fallback_count, empty_result_count, latency_ms_avg, approximate
                from streaming_source_feature_windows
                where source_key = ?
                order by created_at desc
                """,
            this::source,
            sourceKey
        );
    }

    public List<SurfaceFeatureWindow> surfaceWindows(String surfaceKey) {
        return jdbcTemplate.query(
            """
                select surface_key, window_key, requests, degraded_responses, partial_responses, served_count_avg, fallback_count, approximate
                from streaming_surface_feature_windows
                where surface_key = ?
                order by created_at desc
                """,
            this::surface,
            surfaceKey
        );
    }

    public long eventCount(UUID profileId, UUID candidateId, String eventType, Duration duration) {
        return jdbcTemplate.queryForObject(
            """
                select count(distinct event_key)
                from realtime_interaction_events
                where (?::uuid is null or profile_id = ?::uuid)
                  and (?::uuid is null or candidate_profile_id = ?::uuid)
                  and event_type = ?
                  and occurred_at >= now() - (? || ' seconds')::interval
                """,
            Long.class,
            profileId,
            profileId,
            candidateId,
            candidateId,
            eventType,
            String.valueOf(duration.toSeconds())
        );
    }

    public SourceFeatureWindow sourceAggregate(String sourceKey, String windowKey, Duration duration) {
        return jdbcTemplate.queryForObject(
            """
                select
                    coalesce(sum(returned_count), 0) returned_candidates,
                    coalesce(sum(case when timeout then 1 else 0 end), 0) timeout_count,
                    coalesce(sum(case when fallback_used then 1 else 0 end), 0) fallback_count,
                    coalesce(sum(case when returned_count = 0 then 1 else 0 end), 0) empty_count,
                    coalesce(avg(duration_ms), 0) latency_avg
                from source_call_results
                where source_key = ?
                  and started_at >= now() - (? || ' seconds')::interval
                """,
            (rs, rowNum) -> new SourceFeatureWindow(
                sourceKey,
                windowKey,
                rs.getLong("returned_candidates"),
                rs.getLong("timeout_count"),
                rs.getLong("fallback_count"),
                rs.getLong("empty_count"),
                rs.getBigDecimal("latency_avg"),
                true
            ),
            sourceKey,
            String.valueOf(duration.toSeconds())
        );
    }

    public SurfaceFeatureWindow surfaceAggregate(String surfaceKey, String windowKey, Duration duration) {
        return jdbcTemplate.queryForObject(
            """
                select
                    count(*) requests,
                    coalesce(sum(case when degraded then 1 else 0 end), 0) degraded_responses,
                    coalesce(avg(served_count), 0) served_avg
                from multi_stage_serving_requests
                where surface_key = ?
                  and created_at >= now() - (? || ' seconds')::interval
                """,
            (rs, rowNum) -> new SurfaceFeatureWindow(
                surfaceKey,
                windowKey,
                rs.getLong("requests"),
                rs.getLong("degraded_responses"),
                0,
                rs.getBigDecimal("served_avg"),
                degradedFallbackCount(surfaceKey, duration),
                true
            ),
            surfaceKey,
            String.valueOf(duration.toSeconds())
        );
    }

    private long degradedFallbackCount(String surfaceKey, Duration duration) {
        return jdbcTemplate.queryForObject(
            """
                select count(*)
                from serving_quality_runs sq
                join multi_stage_serving_requests ms on ms.id = sq.request_id
                where ms.surface_key = ? and sq.fallback_count > 0
                  and sq.created_at >= now() - (? || ' seconds')::interval
                """,
            Long.class,
            surfaceKey,
            String.valueOf(duration.toSeconds())
        );
    }

    private ProfileFeatureWindow profile(ResultSet rs, int rowNum) throws SQLException {
        return new ProfileFeatureWindow(
            rs.getObject("profile_id", UUID.class),
            rs.getString("window_key"),
            rs.getLong("views"),
            rs.getLong("likes"),
            rs.getLong("passes"),
            rs.getLong("blocks"),
            rs.getLong("reports"),
            rs.getLong("feed_dismisses"),
            rs.getLong("match_creations"),
            rs.getBoolean("approximate")
        );
    }

    private CandidateFeatureWindow candidate(ResultSet rs, int rowNum) throws SQLException {
        long blocks = rs.getLong("blocks");
        long reports = rs.getLong("reports");
        return new CandidateFeatureWindow(
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getString("window_key"),
            rs.getLong("views"),
            rs.getLong("likes"),
            rs.getLong("passes"),
            blocks,
            reports,
            rs.getLong("match_creations"),
            BigDecimal.valueOf(blocks * 2L + reports * 3L),
            rs.getBoolean("approximate")
        );
    }

    private SourceFeatureWindow source(ResultSet rs, int rowNum) throws SQLException {
        return new SourceFeatureWindow(
            rs.getString("source_key"),
            rs.getString("window_key"),
            rs.getLong("returned_candidates"),
            rs.getLong("timeout_count"),
            rs.getLong("fallback_count"),
            rs.getLong("empty_result_count"),
            rs.getBigDecimal("latency_ms_avg"),
            rs.getBoolean("approximate")
        );
    }

    private SurfaceFeatureWindow surface(ResultSet rs, int rowNum) throws SQLException {
        return new SurfaceFeatureWindow(
            rs.getString("surface_key"),
            rs.getString("window_key"),
            rs.getLong("requests"),
            rs.getLong("degraded_responses"),
            rs.getLong("partial_responses"),
            rs.getBigDecimal("served_count_avg"),
            rs.getLong("fallback_count"),
            rs.getBoolean("approximate")
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize streaming JSON", exception);
        }
    }
}
