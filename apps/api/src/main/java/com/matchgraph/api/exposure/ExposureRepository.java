package com.matchgraph.api.exposure;

import java.math.BigDecimal;
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
public class ExposureRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExposureRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createPolicy(ExposurePolicyRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into exposure_control_policies (
                    id, policy_key, name, status, daily_cap, rolling_7_day_cap,
                    policy_window_hours, policy_window_cap, long_tail_boost,
                    overexposure_downrank, new_profile_minimum_boost, config_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            request.policyKey().trim(),
            request.name().trim(),
            request.status() == null || request.status().isBlank() ? "ACTIVE" : request.status().trim(),
            value(request.dailyCap(), 20),
            value(request.rolling7DayCap(), 100),
            value(request.policyWindowHours(), 24),
            value(request.policyWindowCap(), 20),
            value(request.longTailBoost(), BigDecimal.valueOf(0.10)),
            value(request.overexposureDownrank(), BigDecimal.valueOf(0.25)),
            value(request.newProfileMinimumBoost(), BigDecimal.valueOf(0.05)),
            toJson(request.config() == null ? Map.of() : request.config())
        );
        return id;
    }

    public Optional<ExposureControlPolicy> findPolicy(String policyKey) {
        return jdbcTemplate.query(
            """
                select id, policy_key, name, status, daily_cap, rolling_7_day_cap,
                    policy_window_hours, policy_window_cap, long_tail_boost,
                    overexposure_downrank, new_profile_minimum_boost,
                    config_json::text as config_json, created_at, updated_at
                from exposure_control_policies
                where policy_key = ?
                """,
            this::mapPolicy,
            policyKey
        ).stream().findFirst();
    }

    public Optional<ExposureControlPolicy> activePolicy() {
        return jdbcTemplate.query(
            """
                select id, policy_key, name, status, daily_cap, rolling_7_day_cap,
                    policy_window_hours, policy_window_cap, long_tail_boost,
                    overexposure_downrank, new_profile_minimum_boost,
                    config_json::text as config_json, created_at, updated_at
                from exposure_control_policies
                where status = 'ACTIVE'
                order by created_at desc
                limit 1
                """,
            this::mapPolicy
        ).stream().findFirst();
    }

    public void recordExposure(
        UUID viewerProfileId,
        UUID candidateProfileId,
        UUID feedSnapshotId,
        UUID feedItemId,
        UUID decisionLogId,
        String rankingVersion,
        String experimentKey,
        String assignedVariantKey,
        String exposureType,
        int position,
        String contextKey
    ) {
        jdbcTemplate.update(
            """
                insert into candidate_exposure_events (
                    id, candidate_profile_id, viewer_profile_id, feed_snapshot_id, feed_item_id,
                    decision_log_id, ranking_version, experiment_key, assigned_variant_key,
                    exposure_type, position, context_key
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (context_key) do nothing
                """,
            UUID.randomUUID(),
            candidateProfileId,
            viewerProfileId,
            feedSnapshotId,
            feedItemId,
            decisionLogId,
            rankingVersion,
            experimentKey,
            assignedVariantKey,
            exposureType,
            position,
            contextKey
        );
    }

    public int exposureCount(UUID candidateProfileId, int hours) {
        Integer count = jdbcTemplate.queryForObject(
            """
                select count(*)::int
                from candidate_exposure_events
                where candidate_profile_id = ?
                  and exposure_timestamp >= now() - (? * interval '1 hour')
                """,
            Integer.class,
            candidateProfileId,
            hours
        );
        return count == null ? 0 : count;
    }

    public int totalExposureCount(UUID candidateProfileId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*)::int from candidate_exposure_events where candidate_profile_id = ?",
            Integer.class,
            candidateProfileId
        );
        return count == null ? 0 : count;
    }

    public UUID insertAdjustment(
        ExposureControlPolicy policy,
        UUID candidateProfileId,
        UUID viewerProfileId,
        UUID decisionLogId,
        UUID feedSnapshotId,
        String reason,
        BigDecimal boost,
        BigDecimal downrank,
        boolean safetyOverridden,
        Map<String, Object> context
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into exposure_adjustments (
                    id, policy_id, candidate_profile_id, viewer_profile_id, decision_log_id,
                    feed_snapshot_id, adjustment_reason, boost_amount, downrank_amount,
                    bounded, safety_overridden, context_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?::jsonb)
                """,
            id,
            policy.id(),
            candidateProfileId,
            viewerProfileId,
            decisionLogId,
            feedSnapshotId,
            reason,
            boost,
            downrank,
            safetyOverridden,
            toJson(context == null ? Map.of() : context)
        );
        return id;
    }

    public Optional<ExposureAdjustment> latestAdjustment(UUID candidateProfileId, UUID decisionLogId) {
        return jdbcTemplate.query(
            """
                select id, policy_id, candidate_profile_id, viewer_profile_id, decision_log_id,
                    feed_snapshot_id, adjustment_reason, boost_amount, downrank_amount,
                    bounded, safety_overridden, context_json::text as context_json, created_at
                from exposure_adjustments
                where candidate_profile_id = ?
                  and (?::uuid is null or decision_log_id = ?)
                order by created_at desc
                limit 1
                """,
            this::mapAdjustment,
            candidateProfileId,
            decisionLogId,
            decisionLogId
        ).stream().findFirst();
    }

    public List<CandidateExposureEvent> exposures(UUID candidateProfileId) {
        return jdbcTemplate.query(
            """
                select id, candidate_profile_id, viewer_profile_id, feed_snapshot_id, feed_item_id,
                    decision_log_id, ranking_version, experiment_key, assigned_variant_key,
                    exposure_type, position, context_key, exposure_timestamp
                from candidate_exposure_events
                where candidate_profile_id = ?
                order by exposure_timestamp desc
                """,
            this::mapExposure,
            candidateProfileId
        );
    }

    public List<UUID> exposedCandidateIds() {
        return jdbcTemplate.queryForList(
            "select distinct candidate_profile_id from candidate_exposure_events",
            UUID.class
        );
    }

    public void upsertWindow(ExposureControlPolicy policy, UUID candidateProfileId, String windowKey, int hours, int cap) {
        int count = exposureCount(candidateProfileId, hours);
        jdbcTemplate.update(
            """
                insert into candidate_exposure_windows (
                    id, policy_id, candidate_profile_id, window_key, window_start, window_end,
                    exposure_count, exposure_cap, summary_json
                )
                values (?, ?, ?, ?, now() - (? * interval '1 hour'), now(), ?, ?, ?::jsonb)
                on conflict (candidate_profile_id, window_key, window_start, policy_id)
                do nothing
                """,
            UUID.randomUUID(),
            policy.id(),
            candidateProfileId,
            windowKey,
            hours,
            count,
            cap,
            toJson(Map.of("hours", hours, "count", count, "cap", cap))
        );
    }

    private ExposureControlPolicy mapPolicy(ResultSet rs, int rowNum) throws SQLException {
        return new ExposureControlPolicy(
            rs.getObject("id", UUID.class),
            rs.getString("policy_key"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getInt("daily_cap"),
            rs.getInt("rolling_7_day_cap"),
            rs.getInt("policy_window_hours"),
            rs.getInt("policy_window_cap"),
            rs.getBigDecimal("long_tail_boost"),
            rs.getBigDecimal("overexposure_downrank"),
            rs.getBigDecimal("new_profile_minimum_boost"),
            map(rs.getString("config_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private CandidateExposureEvent mapExposure(ResultSet rs, int rowNum) throws SQLException {
        return new CandidateExposureEvent(
            rs.getObject("id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getObject("viewer_profile_id", UUID.class),
            rs.getObject("feed_snapshot_id", UUID.class),
            rs.getObject("feed_item_id", UUID.class),
            rs.getObject("decision_log_id", UUID.class),
            rs.getString("ranking_version"),
            rs.getString("experiment_key"),
            rs.getString("assigned_variant_key"),
            rs.getString("exposure_type"),
            rs.getInt("position"),
            rs.getString("context_key"),
            rs.getObject("exposure_timestamp", OffsetDateTime.class)
        );
    }

    private ExposureAdjustment mapAdjustment(ResultSet rs, int rowNum) throws SQLException {
        return new ExposureAdjustment(
            rs.getObject("id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getObject("viewer_profile_id", UUID.class),
            rs.getObject("decision_log_id", UUID.class),
            rs.getObject("feed_snapshot_id", UUID.class),
            rs.getString("adjustment_reason"),
            rs.getBigDecimal("boost_amount"),
            rs.getBigDecimal("downrank_amount"),
            rs.getBoolean("bounded"),
            rs.getBoolean("safety_overridden"),
            map(rs.getString("context_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private BigDecimal value(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored exposure json is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("exposure value must be JSON serializable", exception);
        }
    }
}
