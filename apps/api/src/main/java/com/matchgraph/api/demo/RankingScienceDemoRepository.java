package com.matchgraph.api.demo;

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
public class RankingScienceDemoRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RankingScienceDemoRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createRun(long seed, Map<String, Object> config) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into ranking_science_demo_runs (id, seed, config_json, status)
                values (?, ?, ?::jsonb, 'RUNNING')
                """,
            id,
            seed,
            toJson(config == null ? Map.of() : config)
        );
        return id;
    }

    public void completeRun(UUID runId, String status, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update ranking_science_demo_runs
                set status = ?,
                    summary_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            status,
            toJson(summary),
            runId
        );
    }

    public UUID insertStep(UUID runId, String name, String status, Map<String, Object> result, long durationMs) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into ranking_science_demo_steps (
                    id, demo_run_id, step_name, step_status, step_result_json, duration_ms
                )
                values (?, ?, ?, ?, ?::jsonb, ?)
                """,
            id,
            runId,
            name,
            status,
            toJson(result == null ? Map.of() : result),
            durationMs
        );
        return id;
    }

    public Optional<RankingScienceDemoRun> findRun(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, seed, config_json::text as config_json, status,
                    summary_json::text as summary_json, created_at, completed_at
                from ranking_science_demo_runs
                where id = ?
                """,
            this::mapRunWithoutSteps,
            runId
        ).stream().findFirst()
            .map(run -> new RankingScienceDemoRun(
                run.id(),
                run.seed(),
                run.config(),
                run.status(),
                run.summary(),
                run.createdAt(),
                run.completedAt(),
                steps(run.id())
            ));
    }

    public List<RankingScienceDemoStep> steps(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, demo_run_id, step_name, step_status, step_result_json::text as step_result_json,
                    duration_ms, created_at
                from ranking_science_demo_steps
                where demo_run_id = ?
                order by created_at, step_name
                """,
            this::mapStep,
            runId
        );
    }

    private RankingScienceDemoRun mapRunWithoutSteps(ResultSet rs, int rowNum) throws SQLException {
        return new RankingScienceDemoRun(
            rs.getObject("id", UUID.class),
            rs.getLong("seed"),
            map(rs.getString("config_json")),
            rs.getString("status"),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            List.of()
        );
    }

    private RankingScienceDemoStep mapStep(ResultSet rs, int rowNum) throws SQLException {
        return new RankingScienceDemoStep(
            rs.getObject("id", UUID.class),
            rs.getObject("demo_run_id", UUID.class),
            rs.getString("step_name"),
            rs.getString("step_status"),
            map(rs.getString("step_result_json")),
            rs.getLong("duration_ms"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored demo json is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("demo value must be JSON serializable", exception);
        }
    }
}
