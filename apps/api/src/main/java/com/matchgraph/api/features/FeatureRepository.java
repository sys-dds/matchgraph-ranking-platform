package com.matchgraph.api.features;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeatureRepository {

    private final JdbcTemplate jdbcTemplate;

    public FeatureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public FeatureResponse upsertProfileFeature(UUID profileId, UpsertFeatureRequest request) {
        return jdbcTemplate.queryForObject(
            """
                insert into profile_features (profile_id, feature_key, feature_value, weight)
                values (?, ?, ?, ?)
                on conflict (profile_id, feature_key, feature_value)
                do update set weight = excluded.weight, updated_at = now()
                returning profile_id as owner_id, feature_key, feature_value, weight, created_at, updated_at
                """,
            this::mapFeature,
            profileId,
            request.featureKey().trim(),
            request.featureValue().trim(),
            request.weight()
        );
    }

    public List<FeatureResponse> findProfileFeatures(UUID profileId) {
        return jdbcTemplate.query(
            """
                select profile_id as owner_id, feature_key, feature_value, weight, created_at, updated_at
                from profile_features
                where profile_id = ?
                order by feature_key, feature_value
                """,
            this::mapFeature,
            profileId
        );
    }

    public FeatureResponse upsertItemFeature(UUID itemId, UpsertFeatureRequest request) {
        return jdbcTemplate.queryForObject(
            """
                insert into item_features (item_id, feature_key, feature_value, weight)
                values (?, ?, ?, ?)
                on conflict (item_id, feature_key, feature_value)
                do update set weight = excluded.weight, updated_at = now()
                returning item_id as owner_id, feature_key, feature_value, weight, created_at, updated_at
                """,
            this::mapFeature,
            itemId,
            request.featureKey().trim(),
            request.featureValue().trim(),
            request.weight()
        );
    }

    public List<FeatureResponse> findItemFeatures(UUID itemId) {
        return jdbcTemplate.query(
            """
                select item_id as owner_id, feature_key, feature_value, weight, created_at, updated_at
                from item_features
                where item_id = ?
                order by feature_key, feature_value
                """,
            this::mapFeature,
            itemId
        );
    }

    private FeatureResponse mapFeature(ResultSet rs, int rowNum) throws SQLException {
        return new FeatureResponse(
            rs.getObject("owner_id", UUID.class),
            rs.getString("feature_key"),
            rs.getString("feature_value"),
            rs.getBigDecimal("weight"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
