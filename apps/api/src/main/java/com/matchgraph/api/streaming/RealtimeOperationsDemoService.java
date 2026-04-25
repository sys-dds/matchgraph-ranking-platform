package com.matchgraph.api.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.streaming.StreamingModels.RealtimeOperationsDemoRun;
import com.matchgraph.api.streaming.StreamingModels.RealtimeRecoveryTrace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RealtimeOperationsDemoService {

    private final OnlineModelKillSwitchService killSwitchService;
    private final RealtimeExperimentGuardrailService guardrailService;
    private final SourceBackpressureService backpressureService;
    private final SourceHealthService sourceHealthService;
    private final CacheInvalidationGraphService cacheGraphService;
    private final RealtimeRecoveryTraceService traceService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RealtimeOperationsDemoService(OnlineModelKillSwitchService killSwitchService, RealtimeExperimentGuardrailService guardrailService, SourceBackpressureService backpressureService, SourceHealthService sourceHealthService, CacheInvalidationGraphService cacheGraphService, RealtimeRecoveryTraceService traceService, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.killSwitchService = killSwitchService;
        this.guardrailService = guardrailService;
        this.backpressureService = backpressureService;
        this.sourceHealthService = sourceHealthService;
        this.cacheGraphService = cacheGraphService;
        this.traceService = traceService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public RealtimeOperationsDemoRun run() {
        UUID demoRunId = UUID.randomUUID();
        jdbcTemplate.update("insert into realtime_operations_demo_runs (id, status, summary_json) values (?, 'COMPLETED', ?::jsonb)", demoRunId, json(Map.of("simulationMarked", true)));
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step(demoRunId, "MODEL_ANOMALY_KILL_SWITCH", modelKillTrace()));
        steps.add(step(demoRunId, "EXPERIMENT_REGRESSION_PAUSE", experimentTrace()));
        steps.add(step(demoRunId, "SOURCE_LATENCY_BACKPRESSURE", sourceBackpressureTrace()));
        steps.add(step(demoRunId, "REPORT_STORM_INVALIDATION", reportStormTrace()));
        steps.add(step(demoRunId, "CACHE_INVALIDATION_GRAPH", cacheGraphTrace()));
        steps.add(step(demoRunId, "RECOVERY_RESTORE", recoveryRestoreTrace()));
        return new RealtimeOperationsDemoRun(demoRunId, "COMPLETED", steps, Map.of("scenarioCount", steps.size(), "storedEvidence", true));
    }

    public RealtimeOperationsDemoRun get(UUID demoRunId) {
        List<Map<String, Object>> steps = jdbcTemplate.query(
            "select scenario_key, status, optional, trace_id, detail_json from realtime_operations_demo_steps where demo_run_id = ? order by created_at",
            (rs, rowNum) -> Map.of(
                "scenarioKey", rs.getString("scenario_key"),
                "status", rs.getString("status"),
                "optional", rs.getBoolean("optional"),
                "traceId", rs.getObject("trace_id", UUID.class),
                "detail", readMap(rs.getString("detail_json"))
            ),
            demoRunId
        );
        return new RealtimeOperationsDemoRun(demoRunId, "COMPLETED", steps, Map.of("scenarioCount", steps.size()));
    }

    private Map<String, Object> step(UUID demoRunId, String scenario, RealtimeRecoveryTrace trace) {
        Map<String, Object> detail = Map.of("traceId", trace.traceId(), "simulation", true, "summary", trace.summary());
        jdbcTemplate.update(
            "insert into realtime_operations_demo_steps (id, demo_run_id, scenario_key, status, optional, trace_id, detail_json) values (?, ?, ?, 'COMPLETED', false, ?, ?::jsonb)",
            UUID.randomUUID(),
            demoRunId,
            scenario,
            trace.traceId(),
            json(detail)
        );
        return Map.of("scenarioKey", scenario, "status", "COMPLETED", "traceId", trace.traceId(), "detail", detail);
    }

    private RealtimeRecoveryTrace modelKillTrace() {
        var state = killSwitchService.kill("demo-final-model", "v1", "simulated MODEL_SCORE_DRIFT anomaly");
        return traceService.create("MODEL_ANOMALY_KILL_SWITCH", true, Map.of("modelBlocked", true, "fallbackRequired", true), List.of(
            Map.of("step", "ANOMALY_DETECTED", "status", "SIMULATED", "detail", "MODEL_SCORE_DRIFT"),
            Map.of("step", "KILL_SWITCH", "status", state.status(), "modelKey", state.modelKey()),
            Map.of("step", "HEAVY_RANK_FALLBACK", "status", "REQUIRED", "reason", "model killed")
        ));
    }

    private RealtimeRecoveryTrace experimentTrace() {
        var run = guardrailService.pauseIfBad("demo-final-experiment");
        return traceService.create("EXPERIMENT_REGRESSION_PAUSE", true, Map.of("guardrailRunId", run.id(), "fallbackToControl", true), List.of(
            Map.of("step", "REGRESSION_DETECTED", "status", "SIMULATED"),
            Map.of("step", "GUARDRAIL_DECISION", "status", "COMPLETED", "decisions", run.decisions().size())
        ));
    }

    private RealtimeRecoveryTrace sourceBackpressureTrace() {
        var action = backpressureService.apply("VECTOR_SIMILARITY", "REDUCE_BUDGET", 6, 2);
        return traceService.create("SOURCE_LATENCY_BACKPRESSURE", true, Map.of("source", action.sourceKey(), "budgetBefore", action.budgetBefore(), "budgetAfter", action.budgetAfter()), List.of(
            Map.of("step", "LATENCY_SPIKE", "status", "SIMULATED"),
            Map.of("step", "BACKPRESSURE", "status", action.action())
        ));
    }

    private RealtimeRecoveryTrace reportStormTrace() {
        var run = cacheGraphService.invalidate("CANDIDATE", "demo-reported-candidate", false);
        return traceService.create("REPORT_STORM_INVALIDATION", true, Map.of("candidateInvalidated", true, "cacheRunId", run.id()), List.of(
            Map.of("step", "REPORT_STORM", "status", "SIMULATED"),
            Map.of("step", "CACHE_INVALIDATION", "status", "COMPLETED", "actions", run.actions().size())
        ));
    }

    private RealtimeRecoveryTrace cacheGraphTrace() {
        cacheGraphService.build();
        var run = cacheGraphService.invalidate("PROFILE", "demo-profile", false);
        return traceService.create("CACHE_INVALIDATION_GRAPH", false, Map.of("runId", run.id(), "globalClear", false), List.of(
            Map.of("step", "GRAPH_BUILD", "status", "COMPLETED"),
            Map.of("step", "AFFECTED_NODES", "status", "COMPLETED", "actions", run.actions().size())
        ));
    }

    private RealtimeRecoveryTrace recoveryRestoreTrace() {
        var model = killSwitchService.restore("demo-final-model", "v1", true);
        var source = backpressureService.restore("VECTOR_SIMILARITY");
        return traceService.create("RECOVERY_RESTORE", false, Map.of("modelStatus", model.status(), "sourceAction", source.action()), List.of(
            Map.of("step", "MODEL_RESTORE", "status", model.status()),
            Map.of("step", "SOURCE_RESTORE", "status", source.action())
        ));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize operations demo JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to read operations demo JSON", exception);
        }
    }
}
