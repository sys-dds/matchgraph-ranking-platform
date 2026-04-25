package com.matchgraph.api.experiment;

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
public class ExperimentRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExperimentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public RankingExperiment create(UUID id, RankingExperimentCreateRequest request) {
        jdbcTemplate.update(
            """
                insert into ranking_experiments (
                    id, experiment_key, name, status, traffic_percentage,
                    holdout_percentage, guardrail_config_json
                )
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            request.experimentKey().trim(),
            request.name().trim(),
            request.status(),
            request.trafficPercentage(),
            request.holdoutPercentage(),
            toJson(request.guardrailConfig() == null ? Map.of() : request.guardrailConfig())
        );
        for (RankingExperimentVariantRequest variant : request.variants()) {
            jdbcTemplate.update(
                """
                    insert into ranking_experiment_variants (
                        id, experiment_id, variant_key, ranking_version,
                        allocation_percentage, config_json
                    )
                    values (?, ?, ?, ?, ?, ?::jsonb)
                    """,
                UUID.randomUUID(),
                id,
                variant.variantKey().trim(),
                variant.rankingVersion().trim(),
                variant.allocationPercentage(),
                toJson(variant.config() == null ? Map.of() : variant.config())
            );
        }
        return find(request.experimentKey()).orElseThrow();
    }

    public Optional<RankingExperiment> find(String experimentKey) {
        List<RankingExperiment> experiments = jdbcTemplate.query(
            """
                select id, experiment_key, name, status, traffic_percentage, holdout_percentage,
                    guardrail_config_json::text as guardrail_config_json, created_at, updated_at
                from ranking_experiments
                where experiment_key = ?
                """,
            this::mapExperimentWithoutVariants,
            experimentKey
        );
        return experiments.stream().findFirst()
            .map(experiment -> new RankingExperiment(
                experiment.id(),
                experiment.experimentKey(),
                experiment.name(),
                experiment.status(),
                experiment.trafficPercentage(),
                experiment.holdoutPercentage(),
                experiment.guardrailConfig(),
                experiment.createdAt(),
                experiment.updatedAt(),
                variants(experiment.id())
            ));
    }

    public Optional<RankingExperimentAssignment> assignment(UUID experimentId, UUID profileId) {
        List<RankingExperimentAssignment> assignments = jdbcTemplate.query(
            """
                select a.id, a.experiment_id, a.profile_id, e.experiment_key, a.assigned_variant_key,
                    a.assigned_ranking_version, a.holdout, a.assignment_reason, a.assignment_hash, a.created_at
                from ranking_experiment_assignments a
                join ranking_experiments e on e.id = a.experiment_id
                where a.experiment_id = ?
                  and a.profile_id = ?
                """,
            this::mapAssignment,
            experimentId,
            profileId
        );
        return assignments.stream().findFirst();
    }

    public RankingExperimentAssignment createAssignment(
        UUID id,
        RankingExperiment experiment,
        UUID profileId,
        String assignedVariantKey,
        String assignedRankingVersion,
        boolean holdout,
        String assignmentReason,
        String assignmentHash
    ) {
        jdbcTemplate.update(
            """
                insert into ranking_experiment_assignments (
                    id, experiment_id, profile_id, assigned_variant_key, assigned_ranking_version,
                    holdout, assignment_reason, assignment_hash
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            id,
            experiment.id(),
            profileId,
            assignedVariantKey,
            assignedRankingVersion,
            holdout,
            assignmentReason,
            assignmentHash
        );
        return assignment(experiment.id(), profileId).orElseThrow();
    }

    private List<RankingExperimentVariant> variants(UUID experimentId) {
        return jdbcTemplate.query(
            """
                select id, experiment_id, variant_key, ranking_version, allocation_percentage,
                    config_json::text as config_json, created_at
                from ranking_experiment_variants
                where experiment_id = ?
                order by variant_key
                """,
            this::mapVariant,
            experimentId
        );
    }

    private RankingExperiment mapExperimentWithoutVariants(ResultSet rs, int rowNum) throws SQLException {
        return new RankingExperiment(
            rs.getObject("id", UUID.class),
            rs.getString("experiment_key"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getBigDecimal("traffic_percentage"),
            rs.getBigDecimal("holdout_percentage"),
            map(rs.getString("guardrail_config_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            List.of()
        );
    }

    private RankingExperimentVariant mapVariant(ResultSet rs, int rowNum) throws SQLException {
        return new RankingExperimentVariant(
            rs.getObject("id", UUID.class),
            rs.getObject("experiment_id", UUID.class),
            rs.getString("variant_key"),
            rs.getString("ranking_version"),
            rs.getBigDecimal("allocation_percentage"),
            map(rs.getString("config_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private RankingExperimentAssignment mapAssignment(ResultSet rs, int rowNum) throws SQLException {
        return new RankingExperimentAssignment(
            rs.getObject("id", UUID.class),
            rs.getObject("experiment_id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getString("experiment_key"),
            rs.getString("assigned_variant_key"),
            rs.getString("assigned_ranking_version"),
            rs.getBoolean("holdout"),
            rs.getString("assignment_reason"),
            rs.getString("assignment_hash"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("experiment value must be JSON serializable", exception);
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored experiment json is invalid", exception);
        }
    }
}
