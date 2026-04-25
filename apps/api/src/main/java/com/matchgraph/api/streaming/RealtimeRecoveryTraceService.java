package com.matchgraph.api.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.streaming.StreamingModels.RealtimeRecoveryTrace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RealtimeRecoveryTraceService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RealtimeRecoveryTraceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public RealtimeRecoveryTrace create(String scenarioKey, boolean degraded, Map<String, Object> summary, List<Map<String, Object>> steps) {
        UUID traceId = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into realtime_recovery_traces (id, scenario_key, status, degraded, summary_json) values (?, ?, 'COMPLETED', ?, ?::jsonb)",
            traceId,
            scenarioKey,
            degraded,
            json(summary)
        );
        for (Map<String, Object> step : steps) {
            jdbcTemplate.update(
                "insert into realtime_recovery_trace_steps (id, trace_id, step_key, status, detail_json) values (?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(),
                traceId,
                String.valueOf(step.getOrDefault("step", "STEP")),
                String.valueOf(step.getOrDefault("status", "COMPLETED")),
                json(step)
            );
        }
        return new RealtimeRecoveryTrace(traceId, scenarioKey, "COMPLETED", degraded, summary, steps);
    }

    public RealtimeRecoveryTrace get(UUID traceId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("select scenario_key, status, degraded, summary_json from realtime_recovery_traces where id = ?", traceId);
        return new RealtimeRecoveryTrace(
            traceId,
            String.valueOf(row.get("scenario_key")),
            String.valueOf(row.get("status")),
            Boolean.TRUE.equals(row.get("degraded")),
            readMap(String.valueOf(row.get("summary_json"))),
            steps(traceId)
        );
    }

    private List<Map<String, Object>> steps(UUID traceId) {
        return jdbcTemplate.query(
            "select detail_json from realtime_recovery_trace_steps where trace_id = ? order by created_at",
            (rs, rowNum) -> readMap(rs.getString("detail_json")),
            traceId
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize recovery trace JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to read recovery trace JSON", exception);
        }
    }
}
