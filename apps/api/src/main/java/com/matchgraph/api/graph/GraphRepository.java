package com.matchgraph.api.graph;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GraphRepository {

    private final JdbcTemplate jdbcTemplate;

    public GraphRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<GraphEdgeResponse> findActiveEdge(UUID sourceProfileId, UUID targetProfileId, String edgeType) {
        List<GraphEdgeResponse> edges = jdbcTemplate.query(
            """
                select id, source_profile_id, target_profile_id, edge_type, status, strength, reason, created_at, updated_at
                from profile_graph_edges
                where source_profile_id = ?
                  and target_profile_id = ?
                  and edge_type = ?
                  and status = 'ACTIVE'
                """,
            this::mapEdge,
            sourceProfileId,
            targetProfileId,
            edgeType
        );
        return edges.stream().findFirst();
    }

    public GraphEdgeResponse createActiveEdge(UUID id, UUID sourceProfileId, UUID targetProfileId, String edgeType, String reason) {
        return jdbcTemplate.queryForObject(
            """
                insert into profile_graph_edges (
                    id, source_profile_id, target_profile_id, edge_type, status, strength, reason
                )
                values (?, ?, ?, ?, 'ACTIVE', 1.0, ?)
                returning id, source_profile_id, target_profile_id, edge_type, status, strength, reason, created_at, updated_at
                """,
            this::mapEdge,
            id,
            sourceProfileId,
            targetProfileId,
            edgeType,
            trimToNull(reason)
        );
    }

    public List<GraphEdgeResponse> deactivateActiveEdges(UUID sourceProfileId, UUID targetProfileId, List<String> edgeTypes) {
        return jdbcTemplate.query(
            """
                update profile_graph_edges
                set status = 'INACTIVE', updated_at = now()
                where source_profile_id = ?
                  and target_profile_id = ?
                  and status = 'ACTIVE'
                  and edge_type = any (?::text[])
                returning id, source_profile_id, target_profile_id, edge_type, status, strength, reason, created_at, updated_at
                """,
            this::mapEdge,
            sourceProfileId,
            targetProfileId,
            edgeTypes.toArray(String[]::new)
        );
    }

    public void recordEvent(UUID id, UUID sourceProfileId, UUID targetProfileId, String edgeType, String action, String reason) {
        jdbcTemplate.update(
            """
                insert into profile_graph_edge_events (id, source_profile_id, target_profile_id, edge_type, action, reason)
                values (?, ?, ?, ?, ?, ?)
                """,
            id,
            sourceProfileId,
            targetProfileId,
            edgeType,
            action,
            trimToNull(reason)
        );
    }

    public List<GraphEdgeResponse> outgoingEdges(UUID sourceProfileId) {
        return jdbcTemplate.query(
            """
                select id, source_profile_id, target_profile_id, edge_type, status, strength, reason, created_at, updated_at
                from profile_graph_edges
                where source_profile_id = ?
                order by updated_at desc, created_at desc
                """,
            this::mapEdge,
            sourceProfileId
        );
    }

    public List<GraphExclusionResponse> safetyExclusions(UUID profileId) {
        return jdbcTemplate.query(
            """
                select distinct target_profile_id as profile_id,
                    case edge_type
                        when 'BLOCK' then 'BLOCKED_EITHER_DIRECTION'
                        when 'MUTE' then 'SUPPRESSED_PROFILE'
                        when 'REPORT' then 'ALREADY_REPORTED'
                        else edge_type
                    end as reason
                from profile_graph_edges
                where source_profile_id = ?
                  and edge_type in ('BLOCK', 'MUTE', 'REPORT')
                  and status = 'ACTIVE'
                union
                select distinct source_profile_id as profile_id, 'BLOCKED_EITHER_DIRECTION' as reason
                from profile_graph_edges
                where target_profile_id = ?
                  and edge_type = 'BLOCK'
                  and status = 'ACTIVE'
                order by reason, profile_id
                """,
            (rs, rowNum) -> new GraphExclusionResponse(
                rs.getObject("profile_id", UUID.class),
                rs.getString("reason")
            ),
            profileId,
            profileId
        );
    }

    public boolean activeBlockEitherDirection(UUID firstProfileId, UUID secondProfileId) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                select exists (
                    select 1
                    from profile_graph_edges
                    where edge_type = 'BLOCK'
                      and status = 'ACTIVE'
                      and (
                        (source_profile_id = ? and target_profile_id = ?)
                        or (source_profile_id = ? and target_profile_id = ?)
                      )
                )
                """,
            Boolean.class,
            firstProfileId,
            secondProfileId,
            secondProfileId,
            firstProfileId
        );
        return Boolean.TRUE.equals(exists);
    }

    private GraphEdgeResponse mapEdge(ResultSet rs, int rowNum) throws SQLException {
        return new GraphEdgeResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("source_profile_id", UUID.class),
            rs.getObject("target_profile_id", UUID.class),
            rs.getString("edge_type"),
            rs.getString("status"),
            rs.getBigDecimal("strength"),
            rs.getString("reason"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class)
        );
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
