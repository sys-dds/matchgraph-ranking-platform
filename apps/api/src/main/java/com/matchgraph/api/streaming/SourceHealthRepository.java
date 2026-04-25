package com.matchgraph.api.streaming;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.streaming.StreamingModels.SourceBackpressureAction;
import com.matchgraph.api.streaming.StreamingModels.SourceHealthSnapshot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SourceHealthRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SourceHealthRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public SourceHealthSnapshot saveSnapshot(String sourceKey, BigDecimal latency, BigDecimal timeoutRate, BigDecimal emptyRate, BigDecimal quality, String status, Map<String, Object> evidence) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into source_health_snapshots (
                    id, source_key, latency_p50_ms, latency_p95_ms, timeout_rate, empty_result_rate,
                    duplicate_rate, safety_filtered_rate, quality_score, health_status, evidence_json
                )
                values (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?::jsonb)
                """,
            id,
            sourceKey,
            latency,
            latency,
            timeoutRate,
            emptyRate,
            quality,
            status,
            json(evidence)
        );
        return new SourceHealthSnapshot(id, sourceKey, status, latency, latency, timeoutRate, emptyRate, quality, evidence);
    }

    public SourceBackpressureAction saveAction(String sourceKey, String action, int before, int after, OffsetDateTime expiresAt, Map<String, Object> reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into source_backpressure_actions (id, source_key, backpressure_action, budget_before, budget_after, expires_at, reason_json) values (?, ?, ?, ?, ?, ?, ?::jsonb)",
            id,
            sourceKey,
            action,
            before,
            after,
            expiresAt,
            json(reason)
        );
        return new SourceBackpressureAction(id, sourceKey, action, before, after, expiresAt, reason);
    }

    public SourceHealthSnapshot latest(String sourceKey) {
        return jdbcTemplate.queryForObject(
            """
                select id, source_key, health_status, latency_p50_ms, latency_p95_ms, timeout_rate, empty_result_rate,
                       quality_score, evidence_json
                from source_health_snapshots
                where source_key = ?
                order by created_at desc limit 1
                """,
            this::snapshot,
            sourceKey
        );
    }

    public SourceBackpressureAction latestAction(String sourceKey) {
        return jdbcTemplate.queryForObject(
            """
                select id, source_key, backpressure_action, budget_before, budget_after, expires_at, reason_json
                from source_backpressure_actions
                where source_key = ? and (expires_at is null or expires_at > now())
                order by created_at desc limit 1
                """,
            this::action,
            sourceKey
        );
    }

    private SourceHealthSnapshot snapshot(ResultSet rs, int rowNum) throws SQLException {
        return new SourceHealthSnapshot(
            rs.getObject("id", UUID.class),
            rs.getString("source_key"),
            rs.getString("health_status"),
            rs.getBigDecimal("latency_p50_ms"),
            rs.getBigDecimal("latency_p95_ms"),
            rs.getBigDecimal("timeout_rate"),
            rs.getBigDecimal("empty_result_rate"),
            rs.getBigDecimal("quality_score"),
            readMap(rs.getString("evidence_json"))
        );
    }

    private SourceBackpressureAction action(ResultSet rs, int rowNum) throws SQLException {
        return new SourceBackpressureAction(
            rs.getObject("id", UUID.class),
            rs.getString("source_key"),
            rs.getString("backpressure_action"),
            rs.getInt("budget_before"),
            rs.getInt("budget_after"),
            rs.getObject("expires_at", OffsetDateTime.class),
            readMap(rs.getString("reason_json"))
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize source health JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to read source health JSON", exception);
        }
    }
}
