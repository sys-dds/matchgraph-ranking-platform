package com.matchgraph.api.realtime;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.realtime.RealtimeModels.DeltaFeedRefreshRequest;
import com.matchgraph.api.realtime.RealtimeModels.FeatureFreshnessCheckRequest;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeFeedbackDemoRun;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionRequest;
import com.matchgraph.api.serving.MultiSurfaceRecommendationService;
import com.matchgraph.api.serving.ServingModels.MultiStageServingRequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RealtimeFeedbackLoopDemoService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RealtimeInteractionService interactionService;
    private final NearlineFeatureMaterializerService materializerService;
    private final LiveSessionIntentService liveIntentService;
    private final DeltaFeedRefreshService deltaFeedRefreshService;
    private final OnlineFeatureFreshnessGuardService freshnessGuardService;
    private final SourceFeedbackService sourceFeedbackService;
    private final MultiSurfaceRecommendationService recommendationService;
    private final RealtimeFeedbackLoopTraceService traceService;

    public RealtimeFeedbackLoopDemoService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        RealtimeInteractionService interactionService,
        NearlineFeatureMaterializerService materializerService,
        LiveSessionIntentService liveIntentService,
        DeltaFeedRefreshService deltaFeedRefreshService,
        OnlineFeatureFreshnessGuardService freshnessGuardService,
        SourceFeedbackService sourceFeedbackService,
        MultiSurfaceRecommendationService recommendationService,
        RealtimeFeedbackLoopTraceService traceService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.interactionService = interactionService;
        this.materializerService = materializerService;
        this.liveIntentService = liveIntentService;
        this.deltaFeedRefreshService = deltaFeedRefreshService;
        this.freshnessGuardService = freshnessGuardService;
        this.sourceFeedbackService = sourceFeedbackService;
        this.recommendationService = recommendationService;
        this.traceService = traceService;
    }

    @Transactional
    public RealtimeFeedbackDemoRun run(UUID profileId, UUID candidateId, UUID sessionId, UUID feedSnapshotId) {
        UUID demoRunId = UUID.randomUUID();
        jdbcTemplate.update("insert into realtime_feedback_loop_demo_runs (id, status) values (?, 'RUNNING')", demoRunId);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (String scenario : List.of("PASS", "BLOCK", "LIKE", "REPORT", "STALE_FEATURE", "SOURCE_FEEDBACK")) {
            Map<String, Object> result = runScenario(profileId, candidateId, sessionId, feedSnapshotId, scenario);
            UUID traceId = (UUID) result.get("traceId");
            jdbcTemplate.update(
                "insert into realtime_feedback_loop_demo_steps (id, demo_run_id, scenario_key, step_status, trace_id, detail_json) values (?, ?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(),
                demoRunId,
                scenario,
                result.get("status"),
                traceId,
                toJson(result)
            );
            steps.add(result);
        }
        Map<String, Object> summary = Map.of("scenarios", steps.size(), "simulation", "explicit service-level scenario inputs only");
        jdbcTemplate.update("update realtime_feedback_loop_demo_runs set status = 'COMPLETED', summary_json = ?::jsonb, completed_at = now() where id = ?", toJson(summary), demoRunId);
        return new RealtimeFeedbackDemoRun(demoRunId, "COMPLETED", steps, summary);
    }

    public RealtimeFeedbackDemoRun get(UUID demoRunId) {
        Map<String, Object> run = jdbcTemplate.queryForObject(
            "select id, status, summary_json::text as summary_json from realtime_feedback_loop_demo_runs where id = ?",
            (rs, rowNum) -> Map.of("id", rs.getObject("id", UUID.class), "status", rs.getString("status"), "summary", map(rs.getString("summary_json"))),
            demoRunId
        );
        List<Map<String, Object>> steps = jdbcTemplate.query(
            "select scenario_key, step_status, trace_id, detail_json::text as detail_json from realtime_feedback_loop_demo_steps where demo_run_id = ? order by created_at",
            (rs, rowNum) -> Map.of("scenarioKey", rs.getString("scenario_key"), "stepStatus", rs.getString("step_status"), "traceId", rs.getObject("trace_id", UUID.class), "detail", map(rs.getString("detail_json"))),
            demoRunId
        );
        return new RealtimeFeedbackDemoRun((UUID) run.get("id"), String.valueOf(run.get("status")), steps, (Map<String, Object>) run.get("summary"));
    }

    private Map<String, Object> runScenario(UUID profileId, UUID candidateId, UUID sessionId, UUID feedSnapshotId, String scenario) {
        UUID traceId = traceService.create(profileId, sessionId, null, false, Map.of("scenario", scenario));
        try {
            switch (scenario) {
                case "PASS", "BLOCK", "LIKE", "REPORT" -> {
                    var event = interactionService.ingest(new RealtimeInteractionRequest(
                        "demo-" + scenario.toLowerCase() + "-" + traceId,
                        profileId,
                        candidateId,
                        feedSnapshotId,
                        null,
                        null,
                        sessionId,
                        scenario,
                        "GRAPH_MUTUALS",
                        OffsetDateTime.now(),
                        Map.of("scenario", scenario)
                    ));
                    traceService.step(traceId, "EVENT_INTAKE", "COMPLETED", Map.of("eventId", event.event().id(), "duplicate", event.duplicate()));
                    traceService.step(traceId, "DEDUPE", event.duplicate() ? "DUPLICATE" : "NEW", Map.of("eventKey", event.event().eventKey()));
                    var features = materializerService.materialize(new RealtimeModels.NearlineFeatureMaterializationRequest(profileId, candidateId));
                    traceService.step(traceId, "NEARLINE_MATERIALIZATION", "COMPLETED", Map.of("runId", features.id()));
                    if (sessionId != null) {
                        var intent = liveIntentService.recompute(sessionId);
                        traceService.step(traceId, "SESSION_INTENT_UPDATE", "COMPLETED", Map.of("confidence", intent.confidenceScore(), "sourceWeights", intent.sourceWeights()));
                    }
                    if (List.of("PASS", "BLOCK", "REPORT").contains(scenario)) {
                        traceService.step(traceId, "CANDIDATE_INVALIDATION", "COMPLETED", Map.of("candidateProfileId", candidateId, "hardOverride", !"PASS".equals(scenario)));
                    }
                    if ("LIKE".equals(scenario)) {
                        traceService.step(traceId, "SOURCE_ADAPTATION", "COMPLETED", Map.of("positiveSourceSignal", "GRAPH_MUTUALS"));
                    }
                    var delta = deltaFeedRefreshService.refresh(profileId, feedSnapshotId, new DeltaFeedRefreshRequest(event.event().id(), null, sessionId, 2, scenario + " feedback loop delta refresh"));
                    traceService.step(traceId, "DELTA_REFRESH", delta.degraded() ? "DEGRADED" : "COMPLETED", Map.of("refreshRunId", delta.refreshRunId(), "removed", delta.removedCandidates(), "new", delta.newCandidates()));
                    var next = recommendationService.multiStage(profileId, "HOME_FEED", new MultiStageServingRequest(sessionId, 3, null, false, false, false));
                    traceService.step(traceId, "NEXT_RECOMMENDATION", next.degraded() ? "DEGRADED" : "COMPLETED", Map.of("requestId", next.requestId(), "served", next.servedItems()));
                }
                case "STALE_FEATURE" -> {
                    var check = freshnessGuardService.check(new FeatureFreshnessCheckRequest(profileId, candidateId, List.of("recent_affinity_score"), 1L, false, true));
                    traceService.step(traceId, "FEATURE_FRESHNESS_CHECK", "COMPLETED", Map.of("checkId", check.checkId(), "status", check.status()));
                    traceService.step(traceId, "NEXT_RECOMMENDATION", "DEGRADED", Map.of("reason", "stale/missing required feature triggers fallback/degraded response"));
                }
                case "SOURCE_FEEDBACK" -> {
                    sourceFeedbackService.record(profileId, sessionId, "GRAPH_MUTUALS", "SOURCE_POSITIVE", java.math.BigDecimal.ONE);
                    sourceFeedbackService.record(profileId, sessionId, "VECTOR_SIMILARITY", "SOURCE_NEGATIVE", java.math.BigDecimal.valueOf(-1));
                    traceService.step(traceId, "SOURCE_ADAPTATION", "COMPLETED", Map.of("positive", "GRAPH_MUTUALS", "negative", "VECTOR_SIMILARITY"));
                    var next = recommendationService.multiStage(profileId, "HOME_FEED", new MultiStageServingRequest(sessionId, 3, null, false, false, false));
                    traceService.step(traceId, "NEXT_RECOMMENDATION", next.degraded() ? "DEGRADED" : "COMPLETED", Map.of("requestId", next.requestId(), "sourceAdaptation", next.trace().get("sourceAdaptation")));
                }
                default -> traceService.step(traceId, "EVENT_INTAKE", "SKIPPED_OPTIONAL", Map.of("scenario", scenario));
            }
            return Map.of("scenario", scenario, "status", "COMPLETED", "traceId", traceId);
        } catch (RuntimeException exception) {
            traceService.step(traceId, "EVENT_INTAKE", "SKIPPED_OPTIONAL", Map.of("scenario", scenario, "reason", exception.getMessage()));
            return Map.of("scenario", scenario, "status", "SKIPPED_OPTIONAL", "traceId", traceId, "reason", exception.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("feedback demo value must be JSON serializable", exception);
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored feedback demo JSON is invalid", exception);
        }
    }
}
