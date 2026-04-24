package com.matchgraph.api.graph;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GraphEdgeRepository {

    private final JdbcTemplate jdbcTemplate;

    public GraphEdgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public GraphEdge upsert(UUID profileId, UUID itemId, String edgeType, BigDecimal strength) {
        return jdbcTemplate.queryForObject(
            """
                insert into graph_edges (source_profile_id, target_item_id, edge_type, strength)
                values (?, ?, ?, ?)
                on conflict (source_profile_id, target_item_id, edge_type)
                do update set strength = excluded.strength, last_seen_at = now()
                returning source_profile_id, target_item_id, edge_type, strength, last_seen_at
                """,
            this::mapEdge,
            profileId,
            itemId,
            edgeType,
            strength
        );
    }

    public List<GraphEdge> findForProfile(UUID profileId) {
        return jdbcTemplate.query(
            """
                select source_profile_id, target_item_id, edge_type, strength, last_seen_at
                from graph_edges
                where source_profile_id = ?
                """,
            this::mapEdge,
            profileId
        );
    }

    public boolean exists(UUID profileId, UUID itemId, String edgeType) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                select exists (
                    select 1
                    from graph_edges
                    where source_profile_id = ?
                      and target_item_id = ?
                      and edge_type = ?
                )
                """,
            Boolean.class,
            profileId,
            itemId,
            edgeType
        );
        return Boolean.TRUE.equals(exists);
    }

    private GraphEdge mapEdge(ResultSet rs, int rowNum) throws SQLException {
        return new GraphEdge(
            rs.getObject("source_profile_id", UUID.class),
            rs.getObject("target_item_id", UUID.class),
            rs.getString("edge_type"),
            rs.getBigDecimal("strength"),
            rs.getObject("last_seen_at", OffsetDateTime.class)
        );
    }
}
