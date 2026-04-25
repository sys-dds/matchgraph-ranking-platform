package com.matchgraph.api.shadow;

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
public class ChampionChallengerRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChampionChallengerRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createConfig(ChampionChallengerConfigRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into champion_challenger_configs (
                    id, config_key, name, status, champion_ranking_version,
                    challenger_ranking_version, guardrail_config_json
                )
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            request.configKey().trim(),
            request.name().trim(),
            request.status() == null || request.status().isBlank() ? "ACTIVE" : request.status().trim(),
            request.championRankingVersion(),
            request.challengerRankingVersion(),
            toJson(request.guardrailConfig() == null ? Map.of() : request.guardrailConfig())
        );
        return id;
    }

    public Optional<ChampionChallengerConfig> findConfig(String configKey) {
        List<ChampionChallengerConfig> configs = jdbcTemplate.query(
            """
                select id, config_key, name, status, champion_ranking_version,
                    challenger_ranking_version, guardrail_config_json::text as guardrail_config_json,
                    created_at, updated_at
                from champion_challenger_configs
                where config_key = ?
                """,
            this::mapConfig,
            configKey
        );
        return configs.stream().findFirst();
    }

    public UUID insertDecision(
        ChampionChallengerConfig config,
        ShadowRankingRun shadowRun,
        int improvedCount,
        int degradedCount,
        BigDecimal topKOverlap,
        BigDecimal averagePositionDelta,
        int safetyRegressionCount,
        String guardrailStatus,
        String promotionRecommendation,
        Map<String, Object> summary
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into champion_challenger_decisions (
                    id, config_id, shadow_run_id, profile_id, baseline_decision_log_id,
                    challenger_improved_count, challenger_degraded_count, top_k_overlap,
                    average_position_delta, safety_regression_count, guardrail_status,
                    promotion_recommendation, summary_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            config.id(),
            shadowRun.id(),
            shadowRun.profileId(),
            shadowRun.baselineDecisionLogId(),
            improvedCount,
            degradedCount,
            topKOverlap,
            averagePositionDelta,
            safetyRegressionCount,
            guardrailStatus,
            promotionRecommendation,
            toJson(summary)
        );
        return id;
    }

    public Optional<ChampionChallengerDecision> findDecision(UUID id) {
        List<ChampionChallengerDecision> decisions = jdbcTemplate.query(
            """
                select id, config_id, shadow_run_id, profile_id, baseline_decision_log_id,
                    challenger_improved_count, challenger_degraded_count, top_k_overlap,
                    average_position_delta, safety_regression_count, guardrail_status,
                    promotion_recommendation, summary_json::text as summary_json, created_at
                from champion_challenger_decisions
                where id = ?
                """,
            this::mapDecision,
            id
        );
        return decisions.stream().findFirst();
    }

    private ChampionChallengerConfig mapConfig(ResultSet rs, int rowNum) throws SQLException {
        return new ChampionChallengerConfig(
            rs.getObject("id", UUID.class),
            rs.getString("config_key"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getString("champion_ranking_version"),
            rs.getString("challenger_ranking_version"),
            map(rs.getString("guardrail_config_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private ChampionChallengerDecision mapDecision(ResultSet rs, int rowNum) throws SQLException {
        return new ChampionChallengerDecision(
            rs.getObject("id", UUID.class),
            rs.getObject("config_id", UUID.class),
            rs.getObject("shadow_run_id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("baseline_decision_log_id", UUID.class),
            rs.getInt("challenger_improved_count"),
            rs.getInt("challenger_degraded_count"),
            rs.getBigDecimal("top_k_overlap"),
            rs.getBigDecimal("average_position_delta"),
            rs.getInt("safety_regression_count"),
            rs.getString("guardrail_status"),
            rs.getString("promotion_recommendation"),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored champion/challenger json is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("champion/challenger value must be JSON serializable", exception);
        }
    }
}
