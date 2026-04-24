package com.matchgraph.api.interaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InteractionRepository {

    private final JdbcTemplate jdbcTemplate;

    public InteractionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public InteractionResponse create(UUID id, UUID profileId, UUID itemId, String interactionType, String metadataJson) {
        return jdbcTemplate.queryForObject(
            """
                insert into interactions (id, profile_id, item_id, interaction_type, metadata)
                values (?, ?, ?, ?, ?::jsonb)
                returning id, profile_id, item_id, interaction_type, occurred_at
                """,
            this::mapInteraction,
            id,
            profileId,
            itemId,
            interactionType,
            metadataJson
        );
    }

    private InteractionResponse mapInteraction(ResultSet rs, int rowNum) throws SQLException {
        return new InteractionResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("item_id", UUID.class),
            rs.getString("interaction_type"),
            rs.getObject("occurred_at", OffsetDateTime.class)
        );
    }
}
