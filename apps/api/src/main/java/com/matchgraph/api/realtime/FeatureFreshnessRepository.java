package com.matchgraph.api.realtime;

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
import com.matchgraph.api.realtime.RealtimeModels.FeatureFreshnessCheck;
import com.matchgraph.api.realtime.RealtimeModels.FeatureFreshnessResult;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeatureFreshnessRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FeatureFreshnessRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createCheck(UUID profileId, UUID candidateId, boolean allowRebuild, boolean allowFallback, String status, Map<String, Object> summary) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into online_feature_freshness_checks (
                    id, profile_id, candidate_profile_id, allow_rebuild, allow_fallback, status, summary_json
                )
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            profileId,
            candidateId,
            allowRebuild,
            allowFallback,
            status,
            toJson(summary)
        );
        return id;
    }

    public void insertResult(UUID checkId, FeatureFreshnessResult result) {
        jdbcTemplate.update(
            """
                insert into online_feature_freshness_results (
                    id, check_id, feature_key, profile_id, candidate_profile_id, age_ms, max_age_ms,
                    status, required, fallback_used, detail_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            checkId,
            result.featureKey(),
            result.profileId(),
            result.candidateProfileId(),
            result.ageMs(),
            result.maxAgeMs(),
            result.status(),
            result.required(),
            result.fallbackUsed(),
            toJson(result.detail())
        );
    }

    public Optional<OffsetDateTime> lastMaterialized(UUID profileId, UUID candidateId, String featureKey) {
        List<OffsetDateTime> values;
        if (featureKey.startsWith("candidate_")) {
            values = jdbcTemplate.queryForList(
                "select last_materialized_at from nearline_candidate_features where candidate_profile_id = ? and feature_key = ?",
                OffsetDateTime.class,
                candidateId,
                featureKey
            );
        } else if (featureKey.startsWith("recent_")) {
            values = jdbcTemplate.queryForList(
                "select last_materialized_at from nearline_pair_features where profile_id = ? and candidate_profile_id = ? and feature_key = ?",
                OffsetDateTime.class,
                profileId,
                candidateId,
                featureKey
            );
        } else {
            values = jdbcTemplate.queryForList(
                "select last_materialized_at from nearline_profile_features where profile_id = ? and feature_key = ?",
                OffsetDateTime.class,
                profileId,
                featureKey
            );
        }
        return values.stream().findFirst();
    }

    public FeatureFreshnessCheck get(UUID checkId) {
        Map<String, Object> check = jdbcTemplate.queryForObject(
            "select id, profile_id, candidate_profile_id, status, summary_json::text as summary_json from online_feature_freshness_checks where id = ?",
            (rs, rowNum) -> Map.of(
                "id", rs.getObject("id", UUID.class),
                "profileId", rs.getObject("profile_id", UUID.class),
                "candidateProfileId", rs.getObject("candidate_profile_id", UUID.class) == null ? "" : rs.getObject("candidate_profile_id", UUID.class),
                "status", rs.getString("status"),
                "summary", map(rs.getString("summary_json"))
            ),
            checkId
        );
        return new FeatureFreshnessCheck(
            (UUID) check.get("id"),
            (UUID) check.get("profileId"),
            check.get("candidateProfileId") instanceof UUID id ? id : null,
            String.valueOf(check.get("status")),
            results(checkId),
            (Map<String, Object>) check.get("summary")
        );
    }

    private List<FeatureFreshnessResult> results(UUID checkId) {
        return jdbcTemplate.query(
            """
                select feature_key, profile_id, candidate_profile_id, age_ms, max_age_ms,
                    status, required, fallback_used, detail_json::text as detail_json
                from online_feature_freshness_results
                where check_id = ?
                order by created_at
                """,
            this::mapResult,
            checkId
        );
    }

    private FeatureFreshnessResult mapResult(ResultSet rs, int rowNum) throws SQLException {
        long age = rs.getLong("age_ms");
        Long nullableAge = rs.wasNull() ? null : age;
        return new FeatureFreshnessResult(
            rs.getString("feature_key"),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            nullableAge,
            rs.getLong("max_age_ms"),
            rs.getString("status"),
            rs.getBoolean("required"),
            rs.getBoolean("fallback_used"),
            map(rs.getString("detail_json"))
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("freshness value must be JSON serializable", exception);
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored freshness JSON is invalid", exception);
        }
    }
}
