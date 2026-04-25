package com.matchgraph.api.streaming;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.streaming.StreamingModels.ModelKillSwitchState;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ModelKillSwitchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ModelKillSwitchRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ModelKillSwitchState kill(String modelKey, String versionKey, String reason, boolean requireReapproval, Map<String, Object> detail) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into model_kill_switch_events (id, model_key, version_key, event_type, kill_reason, detail_json) values (?, ?, ?, 'KILL', ?, ?::jsonb)",
            eventId,
            modelKey,
            versionKey,
            reason,
            json(detail)
        );
        UUID stateId = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into model_kill_switch_states (
                    id, model_key, version_key, status, kill_reason, killed_at, require_rollout_gate_reapproval, detail_json
                )
                values (?, ?, ?, 'KILLED', ?, now(), ?, ?::jsonb)
                on conflict (model_key, version_key) do update set
                    status = 'KILLED',
                    kill_reason = excluded.kill_reason,
                    killed_at = now(),
                    restored_at = null,
                    require_rollout_gate_reapproval = excluded.require_rollout_gate_reapproval,
                    detail_json = excluded.detail_json,
                    updated_at = now()
                """,
            stateId,
            modelKey,
            versionKey,
            reason,
            requireReapproval,
            json(detail)
        );
        return state(modelKey, versionKey);
    }

    public ModelKillSwitchState restore(String modelKey, String versionKey, boolean emergencyRestore, Map<String, Object> detail) {
        jdbcTemplate.update(
            "insert into model_kill_switch_events (id, model_key, version_key, event_type, detail_json) values (?, ?, ?, 'RESTORE', ?::jsonb)",
            UUID.randomUUID(),
            modelKey,
            versionKey,
            json(detail)
        );
        jdbcTemplate.update(
            """
                insert into model_kill_switch_states (
                    id, model_key, version_key, status, restored_at, require_rollout_gate_reapproval, detail_json
                )
                values (?, ?, ?, 'RESTORED', now(), ?, ?::jsonb)
                on conflict (model_key, version_key) do update set
                    status = 'RESTORED',
                    restored_at = now(),
                    require_rollout_gate_reapproval = excluded.require_rollout_gate_reapproval,
                    detail_json = excluded.detail_json,
                    updated_at = now()
                """,
            UUID.randomUUID(),
            modelKey,
            versionKey,
            !emergencyRestore,
            json(detail)
        );
        return state(modelKey, versionKey);
    }

    public boolean killed(String modelKey, String versionKey) {
        Boolean result = jdbcTemplate.queryForObject(
            "select count(*) > 0 from model_kill_switch_states where model_key = ? and version_key = ? and status = 'KILLED'",
            Boolean.class,
            modelKey,
            versionKey
        );
        return Boolean.TRUE.equals(result);
    }

    public ModelKillSwitchState state(String modelKey, String versionKey) {
        return jdbcTemplate.queryForObject(
            """
                select id, model_key, version_key, status, kill_reason, require_rollout_gate_reapproval, detail_json
                from model_kill_switch_states
                where model_key = ? and version_key = ?
                """,
            this::state,
            modelKey,
            versionKey
        );
    }

    private ModelKillSwitchState state(ResultSet rs, int rowNum) throws SQLException {
        return new ModelKillSwitchState(
            rs.getObject("id", UUID.class),
            rs.getString("model_key"),
            rs.getString("version_key"),
            rs.getString("status"),
            rs.getString("kill_reason"),
            rs.getBoolean("require_rollout_gate_reapproval"),
            readMap(rs.getString("detail_json"))
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize kill switch JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to read kill switch JSON", exception);
        }
    }
}
