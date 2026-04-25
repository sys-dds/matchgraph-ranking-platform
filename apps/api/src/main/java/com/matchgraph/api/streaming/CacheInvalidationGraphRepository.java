package com.matchgraph.api.streaming;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.streaming.StreamingModels.CacheInvalidationAction;
import com.matchgraph.api.streaming.StreamingModels.CacheInvalidationNode;
import com.matchgraph.api.streaming.StreamingModels.CacheInvalidationRun;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CacheInvalidationGraphRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CacheInvalidationGraphRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public CacheInvalidationNode upsertNode(String type, String ref, Map<String, Object> detail) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into cache_invalidation_nodes (id, node_type, node_ref, detail_json)
                values (?, ?, ?, ?::jsonb)
                on conflict (node_type, node_ref) do update set detail_json = excluded.detail_json
                """,
            id,
            type,
            ref,
            json(detail)
        );
        return node(type, ref);
    }

    public void edge(CacheInvalidationNode from, CacheInvalidationNode to, String edgeType) {
        jdbcTemplate.update(
            "insert into cache_invalidation_edges (id, from_node_id, to_node_id, edge_type) values (?, ?, ?, ?) on conflict do nothing",
            UUID.randomUUID(),
            from.id(),
            to.id(),
            edgeType
        );
    }

    public List<CacheInvalidationNode> affected(String nodeType, String nodeRef) {
        return jdbcTemplate.query(
            """
                with recursive start_node as (
                    select id from cache_invalidation_nodes where node_type = ? and node_ref = ?
                ), affected_nodes(id) as (
                    select id from start_node
                    union
                    select e.to_node_id from cache_invalidation_edges e join affected_nodes a on a.id = e.from_node_id
                )
                select n.id, n.node_type, n.node_ref, n.detail_json
                from cache_invalidation_nodes n
                join affected_nodes a on a.id = n.id
                order by n.node_type, n.node_ref
                """,
            this::node,
            nodeType,
            nodeRef
        );
    }

    public CacheInvalidationRun saveRun(String triggerType, String triggerRef, boolean global, List<CacheInvalidationAction> actions, Map<String, Object> summary) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into cache_invalidation_runs (id, trigger_node_type, trigger_node_ref, global_invalidation, status, summary_json) values (?, ?, ?, ?, 'COMPLETED', ?::jsonb)",
            runId,
            triggerType,
            triggerRef,
            global,
            json(summary)
        );
        for (CacheInvalidationAction action : actions) {
            jdbcTemplate.update(
                "insert into cache_invalidation_actions (id, run_id, action_type, target_node_type, target_node_ref, execution_status, reason_json) values (?, ?, ?, ?, ?, ?, ?::jsonb)",
                action.id(),
                runId,
                action.actionType(),
                action.targetNodeType(),
                action.targetNodeRef(),
                action.executionStatus(),
                json(action.reason())
            );
        }
        return new CacheInvalidationRun(runId, triggerType, triggerRef, global, "COMPLETED", actions, summary);
    }

    public CacheInvalidationRun run(UUID runId) {
        Map<String, Object> summary = jdbcTemplate.queryForObject(
            "select summary_json from cache_invalidation_runs where id = ?",
            (rs, rowNum) -> readMap(rs.getString("summary_json")),
            runId
        );
        Map<String, Object> run = jdbcTemplate.queryForMap("select trigger_node_type, trigger_node_ref, global_invalidation from cache_invalidation_runs where id = ?", runId);
        return new CacheInvalidationRun(runId, String.valueOf(run.get("trigger_node_type")), String.valueOf(run.get("trigger_node_ref")), Boolean.TRUE.equals(run.get("global_invalidation")), "COMPLETED", actions(runId), summary);
    }

    public List<CacheInvalidationAction> actions(UUID runId) {
        return jdbcTemplate.query(
            "select id, action_type, target_node_type, target_node_ref, execution_status, reason_json from cache_invalidation_actions where run_id = ? order by created_at",
            this::action,
            runId
        );
    }

    private CacheInvalidationNode node(String type, String ref) {
        return jdbcTemplate.queryForObject(
            "select id, node_type, node_ref, detail_json from cache_invalidation_nodes where node_type = ? and node_ref = ?",
            this::node,
            type,
            ref
        );
    }

    private CacheInvalidationNode node(ResultSet rs, int rowNum) throws SQLException {
        return new CacheInvalidationNode(rs.getObject("id", UUID.class), rs.getString("node_type"), rs.getString("node_ref"), readMap(rs.getString("detail_json")));
    }

    private CacheInvalidationAction action(ResultSet rs, int rowNum) throws SQLException {
        return new CacheInvalidationAction(rs.getObject("id", UUID.class), rs.getString("action_type"), rs.getString("target_node_type"), rs.getString("target_node_ref"), rs.getString("execution_status"), readMap(rs.getString("reason_json")));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize cache invalidation JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to read cache invalidation JSON", exception);
        }
    }
}
