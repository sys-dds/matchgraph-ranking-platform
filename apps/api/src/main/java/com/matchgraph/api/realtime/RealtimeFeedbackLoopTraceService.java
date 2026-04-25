package com.matchgraph.api.realtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeFeedbackTrace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RealtimeFeedbackLoopTraceService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RealtimeFeedbackLoopTraceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID create(UUID profileId, UUID sessionId, UUID eventId, boolean degraded, Map<String, Object> summary) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into realtime_feedback_loop_traces (id, profile_id, session_id, event_id, status, degraded, summary_json)
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            profileId,
            sessionId,
            eventId,
            degraded ? "DEGRADED" : "COMPLETED",
            degraded,
            toJson(summary)
        );
        return id;
    }

    public void step(UUID traceId, String key, String status, Map<String, Object> detail) {
        jdbcTemplate.update(
            """
                insert into realtime_feedback_loop_trace_steps (id, trace_id, step_key, step_status, detail_json)
                values (?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            traceId,
            key,
            status,
            toJson(detail)
        );
    }

    public RealtimeFeedbackTrace get(UUID traceId) {
        Map<String, Object> trace = jdbcTemplate.queryForObject(
            "select id, status, degraded, summary_json::text as summary_json from realtime_feedback_loop_traces where id = ?",
            (rs, rowNum) -> Map.of(
                "id", rs.getObject("id", UUID.class),
                "status", rs.getString("status"),
                "degraded", rs.getBoolean("degraded"),
                "summary", map(rs.getString("summary_json"))
            ),
            traceId
        );
        List<Map<String, Object>> steps = jdbcTemplate.query(
            """
                select step_key, step_status, detail_json::text as detail_json
                from realtime_feedback_loop_trace_steps
                where trace_id = ?
                order by created_at
                """,
            (rs, rowNum) -> Map.of(
                "stepKey", rs.getString("step_key"),
                "stepStatus", rs.getString("step_status"),
                "detail", map(rs.getString("detail_json"))
            ),
            traceId
        );
        return new RealtimeFeedbackTrace((UUID) trace.get("id"), String.valueOf(trace.get("status")), Boolean.TRUE.equals(trace.get("degraded")), (Map<String, Object>) trace.get("summary"), steps);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("feedback trace value must be JSON serializable", exception);
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored feedback trace JSON is invalid", exception);
        }
    }
}
