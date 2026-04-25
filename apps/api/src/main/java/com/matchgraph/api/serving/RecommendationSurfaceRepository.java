package com.matchgraph.api.serving;

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
import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.serving.ServingModels.RecommendationSession;
import com.matchgraph.api.serving.ServingModels.ServedItem;
import com.matchgraph.api.serving.ServingModels.SourceCallResult;
import com.matchgraph.api.serving.ServingModels.SurfaceConfig;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecommendationSurfaceRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RecommendationSurfaceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void upsertSurface(SurfaceConfig config) {
        jdbcTemplate.update(
            """
                insert into recommendation_surfaces (id, surface_key, status)
                values (?, ?, ?)
                on conflict (surface_key) do update set status = excluded.status
                """,
            UUID.randomUUID(),
            config.surfaceKey(),
            config.status()
        );
        jdbcTemplate.update(
            """
                insert into recommendation_surface_configs (
                    id, surface_key, status, ranking_version, allowed_sources_json, result_size,
                    latency_budget_ms, freshness_config_json, diversity_config_json, fallback_config_json, safety_config_json
                )
                values (?, ?, 'ACTIVE', ?, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
                """,
            UUID.randomUUID(),
            config.surfaceKey(),
            config.rankingVersion(),
            toJson(config.allowedSources()),
            config.resultSize(),
            config.latencyBudgetMs(),
            toJson(config.freshnessConfig()),
            toJson(config.diversityConfig()),
            toJson(config.fallbackConfig()),
            toJson(config.safetyConfig())
        );
    }

    public Optional<SurfaceConfig> surface(String surfaceKey) {
        return jdbcTemplate.query(
            """
                select s.surface_key, s.status as surface_status, c.ranking_version,
                    c.allowed_sources_json::text as allowed_sources_json, c.result_size, c.latency_budget_ms,
                    c.freshness_config_json::text as freshness_config_json,
                    c.diversity_config_json::text as diversity_config_json,
                    c.fallback_config_json::text as fallback_config_json,
                    c.safety_config_json::text as safety_config_json
                from recommendation_surfaces s
                join recommendation_surface_configs c on c.surface_key = s.surface_key and c.status = 'ACTIVE'
                where s.surface_key = ?
                order by c.created_at desc
                limit 1
                """,
            this::mapSurface,
            surfaceKey
        ).stream().findFirst();
    }

    public UUID createServingRequest(UUID profileId, String surfaceKey, UUID sessionId, boolean degraded, int servedCount, Map<String, Object> trace, Map<String, Object> result) {
        UUID id = UUID.randomUUID();
        createServingRequestWithId(id, profileId, surfaceKey, sessionId, degraded, servedCount, trace, result);
        return id;
    }

    public void createServingRequestWithId(UUID id, UUID profileId, String surfaceKey, UUID sessionId, boolean degraded, int servedCount, Map<String, Object> trace, Map<String, Object> result) {
        jdbcTemplate.update(
            """
                insert into multi_stage_serving_requests (
                    id, profile_id, surface_key, session_id, status, degraded, served_count, trace_json, result_json
                )
                values (?, ?, ?, ?, 'COMPLETED', ?, ?, ?::jsonb, ?::jsonb)
                """,
            id,
            profileId,
            surfaceKey,
            sessionId,
            degraded,
            servedCount,
            toJson(trace),
            toJson(result)
        );
    }

    public void updateServingTrace(UUID requestId, Map<String, Object> trace, Map<String, Object> result) {
        jdbcTemplate.update(
            """
                update multi_stage_serving_requests
                set trace_json = ?::jsonb,
                    result_json = ?::jsonb
                where id = ?
                """,
            toJson(trace),
            toJson(result),
            requestId
        );
    }

    public UUID createSourceRoutingPlan(UUID requestId, SurfaceConfig surface, UUID sessionId, Map<String, Object> plan) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into source_routing_plans (id, request_id, surface_key, session_id, plan_json)
                values (?, ?, ?, ?, ?::jsonb)
                """,
            id,
            requestId,
            surface.surfaceKey(),
            sessionId,
            toJson(plan)
        );
        return id;
    }

    public void insertSourceRoutingPlanItem(UUID planId, String sourceKey, int priority, int maxCandidates, int timeoutMs, String fallbackSource, String healthStatus, java.math.BigDecimal qualityScore, Map<String, Object> detail) {
        jdbcTemplate.update(
            """
                insert into source_routing_plan_items (
                    id, plan_id, source_key, priority, max_candidates, timeout_budget_ms,
                    cost_weight, fallback_source, health_status, quality_score
                )
                values (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                """,
            UUID.randomUUID(),
            planId,
            sourceKey,
            priority,
            maxCandidates,
            timeoutMs,
            fallbackSource,
            healthStatus,
            qualityScore
        );
    }

    public void insertSourceCallResult(UUID requestId, SourceCallResult result) {
        jdbcTemplate.update(
            """
                insert into source_call_results (
                    id, request_id, source_key, completed_at, duration_ms, returned_count,
                    timeout, degraded, fallback_used, fallback_source, degraded_reason, candidates_json
                )
                values (?, ?, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            requestId,
            result.sourceKey(),
            result.durationMs(),
            result.returnedCount(),
            result.timeout(),
            result.degraded(),
            result.fallbackUsed(),
            result.fallbackSource(),
            result.degradedReason(),
            toJson(result.candidates())
        );
    }

    public void insertTraceStep(UUID requestId, String stepName, String status, int durationMs, Map<String, Object> detail) {
        jdbcTemplate.update(
            """
                insert into multi_stage_serving_trace_steps (id, request_id, step_name, status, duration_ms, detail_json)
                values (?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            requestId,
            stepName,
            status,
            durationMs,
            toJson(detail)
        );
    }

    public void insertResult(UUID requestId, ServedItem item) {
        jdbcTemplate.update(
            """
                insert into multi_stage_serving_results (
                    id, request_id, candidate_profile_id, position, score, source_types_json, reasons_json
                )
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """,
            UUID.randomUUID(),
            requestId,
            item.candidateProfileId(),
            item.position(),
            item.score(),
            toJson(item.sourceTypes()),
            toJson(item.reasons())
        );
    }

    public Optional<Map<String, Object>> trace(UUID requestId) {
        return jdbcTemplate.query(
            """
                select trace_json::text as trace_json
                from multi_stage_serving_requests
                where id = ?
                """,
            (rs, rowNum) -> map(rs.getString("trace_json")),
            requestId
        ).stream().findFirst();
    }

    public UUID createSession(UUID profileId, int ttlMinutes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into recommendation_sessions (id, profile_id, expires_at)
                values (?, ?, now() + (? || ' minutes')::interval)
                """,
            id,
            profileId,
            ttlMinutes
        );
        jdbcTemplate.update(
            """
                insert into session_intent_states (
                    id, session_id, profile_id, source_preference_weights_json,
                    recent_positive_source_signals_json, recent_negative_source_signals_json,
                    current_intent_json, expires_at
                )
                values (?, ?, ?, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, ?::jsonb, now() + (? || ' minutes')::interval)
                """,
            UUID.randomUUID(),
            id,
            profileId,
            toJson(Map.of("explanation", "short-lived session intent")),
            ttlMinutes
        );
        return id;
    }

    public Optional<RecommendationSession> session(UUID sessionId) {
        return jdbcTemplate.query(
            """
                select id, profile_id, expires_at, status
                from recommendation_sessions
                where id = ?
                """,
            (rs, rowNum) -> new RecommendationSession(rs.getObject("id", UUID.class), rs.getObject("profile_id", UUID.class), rs.getObject("expires_at", OffsetDateTime.class), rs.getString("status")),
            sessionId
        ).stream().findFirst();
    }

    public void insertIntentEvent(UUID sessionId, String eventType, String sourceKey, UUID candidateProfileId, Map<String, Object> metadata) {
        jdbcTemplate.update(
            """
                insert into session_intent_events (id, session_id, event_type, source_key, candidate_profile_id, metadata_json)
                values (?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            sessionId,
            eventType,
            sourceKey,
            candidateProfileId,
            toJson(metadata == null ? Map.of() : metadata)
        );
    }

    public List<Map<String, Object>> intentEvents(UUID sessionId) {
        return jdbcTemplate.query(
            """
                select event_type, source_key, candidate_profile_id::text as candidate_profile_id
                from session_intent_events
                where session_id = ?
                order by created_at
                """,
            (rs, rowNum) -> Map.of(
                "eventType", rs.getString("event_type"),
                "sourceKey", rs.getString("source_key") == null ? "" : rs.getString("source_key"),
                "candidateProfileId", rs.getString("candidate_profile_id") == null ? "" : rs.getString("candidate_profile_id")
            ),
            sessionId
        );
    }

    public void insertFatigue(UUID profileId, UUID candidateId, String source, String reason, int minutes, int repetitionCount) {
        jdbcTemplate.update(
            """
                insert into fatigue_suppression_windows (
                    id, profile_id, candidate_profile_id, source_type, suppression_reason,
                    suppress_until, fatigue_score, repetition_count
                )
                values (?, ?, ?, ?, ?, now() + (? || ' minutes')::interval, ?, ?)
                """,
            UUID.randomUUID(),
            profileId,
            candidateId,
            source,
            reason,
            minutes,
            java.math.BigDecimal.valueOf(repetitionCount),
            repetitionCount
        );
    }

    public boolean fatigued(UUID profileId, UUID candidateId, String source) {
        Integer count = jdbcTemplate.queryForObject(
            """
                select count(*)
                from fatigue_suppression_windows
                where profile_id = ?
                  and suppress_until > now()
                  and ((candidate_profile_id = ?) or (source_type = ?))
                """,
            Integer.class,
            profileId,
            candidateId,
            source
        );
        return count != null && count > 0;
    }

    public UUID insertServingQuality(UUID requestId, boolean degraded, int fallbackCount, int timeoutCount, int partialResultCount, List<String> warnings) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into serving_quality_runs (
                    id, request_id, status, degraded, fallback_count, timeout_count,
                    partial_result_count, quality_warning, summary_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            runId,
            requestId,
            degraded ? "WARN" : "PASS",
            degraded,
            fallbackCount,
            timeoutCount,
            partialResultCount,
            warnings.isEmpty() ? null : String.join("; ", warnings),
            toJson(Map.of("approximate", true, "warnings", warnings))
        );
        insertStageMetric(runId, "source_routing", 5, 50, "PASS", false, false, false, null);
        insertStageMetric(runId, "retrieval", timeoutCount > 0 ? 200 : 20, 100, timeoutCount > 0 ? "WARN" : "PASS", timeoutCount > 0, fallbackCount > 0, false, timeoutCount > 0 ? "source timeout simulated" : null);
        insertStageMetric(runId, "pre_rank", 5, 50, "PASS", false, false, false, null);
        insertStageMetric(runId, "heavy_rank", fallbackCount > 0 ? 150 : 30, 100, fallbackCount > 0 ? "WARN" : "PASS", fallbackCount > 0, fallbackCount > 0, false, fallbackCount > 0 ? "model fallback" : null);
        insertStageMetric(runId, "slate_optimize", 5, 50, partialResultCount > 0 ? "WARN" : "PASS", partialResultCount > 0, false, partialResultCount > 0, partialResultCount > 0 ? "partial slate" : null);
        return runId;
    }

    private void insertStageMetric(UUID runId, String stage, int duration, int budget, String status, boolean degraded, boolean fallback, boolean partial, String warning) {
        jdbcTemplate.update(
            """
                insert into serving_quality_stage_metrics (
                    id, run_id, stage_name, duration_ms, budget_ms, status, degraded, fallback_used, partial_result, quality_warning
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            UUID.randomUUID(),
            runId,
            stage,
            duration,
            budget,
            status,
            degraded,
            fallback,
            partial,
            warning
        );
    }

    public UUID createDemoRun() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("insert into multi_stage_serving_demo_runs (id, status) values (?, 'RUNNING')", id);
        return id;
    }

    public void insertDemoStep(UUID demoRunId, String step, String status, UUID traceId, Map<String, Object> detail) {
        jdbcTemplate.update(
            """
                insert into multi_stage_serving_demo_steps (id, demo_run_id, step_key, step_status, trace_id, detail_json)
                values (?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            demoRunId,
            step,
            status,
            traceId,
            toJson(detail)
        );
    }

    public void completeDemo(UUID demoRunId, Map<String, Object> summary) {
        jdbcTemplate.update(
            "update multi_stage_serving_demo_runs set status = 'COMPLETED', summary_json = ?::jsonb, completed_at = now() where id = ?",
            toJson(summary),
            demoRunId
        );
    }

    public List<Map<String, Object>> demoSteps(UUID demoRunId) {
        return jdbcTemplate.query(
            """
                select step_key, step_status, trace_id::text as trace_id, detail_json::text as detail_json
                from multi_stage_serving_demo_steps
                where demo_run_id = ?
                order by created_at
                """,
            (rs, rowNum) -> Map.of(
                "stepKey", rs.getString("step_key"),
                "stepStatus", rs.getString("step_status"),
                "traceId", rs.getString("trace_id") == null ? "" : rs.getString("trace_id"),
                "detail", map(rs.getString("detail_json"))
            ),
            demoRunId
        );
    }

    private SurfaceConfig mapSurface(ResultSet rs, int rowNum) throws SQLException {
        return new SurfaceConfig(
            rs.getString("surface_key"),
            rs.getString("surface_status"),
            rs.getString("ranking_version"),
            list(rs.getString("allowed_sources_json")),
            rs.getInt("result_size"),
            rs.getInt("latency_budget_ms"),
            map(rs.getString("freshness_config_json")),
            map(rs.getString("diversity_config_json")),
            map(rs.getString("fallback_config_json")),
            map(rs.getString("safety_config_json"))
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored serving JSON is invalid", exception);
        }
    }

    private List<String> list(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored serving list JSON is invalid", exception);
        }
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("serving value must be JSON serializable", exception);
        }
    }
}
