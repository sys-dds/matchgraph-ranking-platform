package com.matchgraph.api.feed;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RankableItemRepository {

    private final JdbcTemplate jdbcTemplate;

    public RankableItemRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ItemResponse create(CreateItemRequest request) {
        UUID id = UUID.randomUUID();
        return jdbcTemplate.queryForObject(
            """
                insert into rankable_items (id, external_ref, title, item_type, status)
                values (?, ?, ?, ?, ?)
                returning id, external_ref, title, item_type, status, created_at, updated_at
                """,
            this::mapItem,
            id,
            request.externalRef().trim(),
            request.title().trim(),
            request.itemType(),
            request.status()
        );
    }

    public Optional<ItemResponse> findById(UUID id) {
        List<ItemResponse> items = jdbcTemplate.query(
            """
                select id, external_ref, title, item_type, status, created_at, updated_at
                from rankable_items
                where id = ?
                """,
            this::mapItem,
            id
        );
        return items.stream().findFirst();
    }

    public List<ItemResponse> find(String itemType, String status, int limit) {
        return jdbcTemplate.query(
            """
                select id, external_ref, title, item_type, status, created_at, updated_at
                from rankable_items
                where (? is null or item_type = ?)
                  and (? is null or status = ?)
                order by created_at desc, id
                limit ?
                """,
            this::mapItem,
            itemType,
            itemType,
            status,
            status,
            limit
        );
    }

    public List<ItemResponse> findActiveExcludingHidden(UUID profileId, int limit) {
        return jdbcTemplate.query(
            """
                select i.id, i.external_ref, i.title, i.item_type, i.status, i.created_at, i.updated_at
                from rankable_items i
                where i.status = 'ACTIVE'
                  and not exists (
                      select 1
                      from graph_edges ge
                      where ge.source_profile_id = ?
                        and ge.target_item_id = i.id
                        and ge.edge_type = 'HIDDEN'
                  )
                order by i.created_at desc, i.id
                limit ?
                """,
            this::mapItem,
            profileId,
            limit
        );
    }

    public boolean exists(UUID id) {
        Boolean exists = jdbcTemplate.queryForObject("select exists (select 1 from rankable_items where id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(exists);
    }

    private ItemResponse mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new ItemResponse(
            rs.getObject("id", UUID.class),
            rs.getString("external_ref"),
            rs.getString("title"),
            rs.getString("item_type"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
