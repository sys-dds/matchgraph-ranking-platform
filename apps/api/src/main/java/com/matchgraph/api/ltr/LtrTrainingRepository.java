package com.matchgraph.api.ltr;

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
public class LtrTrainingRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LtrTrainingRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createRun(LtrTrainingRequest request, List<String> featureNames, Map<String, Object> config) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into ltr_training_runs (
                    id, model_key, version_key, dataset_run_id, algorithm, status,
                    feature_names_json, config_json
                )
                values (?, ?, ?, ?, ?, 'RUNNING', ?::jsonb, ?::jsonb)
                """,
            id,
            request.modelKey(),
            request.versionKey(),
            request.datasetRunId(),
            request.algorithm(),
            toJson(featureNames),
            toJson(config)
        );
        return id;
    }

    public void completeRun(UUID runId, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update ltr_training_runs
                set status = 'COMPLETED',
                    summary_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            toJson(summary),
            runId
        );
    }

    public void insertMetrics(UUID runId, LocalLinearRankerTrainer.TrainingResult result) {
        jdbcTemplate.update(
            """
                insert into ltr_training_metrics (
                    id, training_run_id, training_example_count, validation_example_count,
                    positive_count, negative_count, validation_precision_at_k,
                    validation_average_reward, feature_coverage, metrics_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            result.trainingExampleCount(),
            result.validationExampleCount(),
            result.positiveCount(),
            result.negativeCount(),
            result.validationPrecisionAtK(),
            result.validationAverageReward(),
            result.featureCoverage(),
            toJson(result.metrics())
        );
    }

    public void insertSnapshot(UUID runId, UUID exampleId, String split, Map<String, Object> features, BigDecimal labelValue, BigDecimal score) {
        jdbcTemplate.update(
            """
                insert into ltr_training_examples_snapshot (
                    id, training_run_id, training_example_id, split, feature_values_json, label_value, score
                )
                values (?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
            UUID.randomUUID(),
            runId,
            exampleId,
            split,
            toJson(features),
            labelValue,
            score
        );
    }

    public Optional<LtrTrainingRun> findRun(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, model_key, version_key, dataset_run_id, algorithm, status,
                    feature_names_json::text as feature_names_json,
                    config_json::text as config_json, summary_json::text as summary_json,
                    created_at, completed_at
                from ltr_training_runs
                where id = ?
                """,
            this::mapRun,
            runId
        ).stream().findFirst();
    }

    public Optional<LtrTrainingMetrics> metrics(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, training_run_id, training_example_count, validation_example_count,
                    positive_count, negative_count, validation_precision_at_k, validation_average_reward,
                    feature_coverage, metrics_json::text as metrics_json, created_at
                from ltr_training_metrics
                where training_run_id = ?
                """,
            this::mapMetrics,
            runId
        ).stream().findFirst();
    }

    private LtrTrainingRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new LtrTrainingRun(
            rs.getObject("id", UUID.class),
            rs.getString("model_key"),
            rs.getString("version_key"),
            rs.getObject("dataset_run_id", UUID.class),
            rs.getString("algorithm"),
            rs.getString("status"),
            list(rs.getString("feature_names_json")),
            map(rs.getString("config_json")),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class)
        );
    }

    private LtrTrainingMetrics mapMetrics(ResultSet rs, int rowNum) throws SQLException {
        return new LtrTrainingMetrics(
            rs.getObject("id", UUID.class),
            rs.getObject("training_run_id", UUID.class),
            rs.getInt("training_example_count"),
            rs.getInt("validation_example_count"),
            rs.getInt("positive_count"),
            rs.getInt("negative_count"),
            rs.getBigDecimal("validation_precision_at_k"),
            rs.getBigDecimal("validation_average_reward"),
            rs.getBigDecimal("feature_coverage"),
            map(rs.getString("metrics_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored training JSON is invalid", exception);
        }
    }

    private List<String> list(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored training list JSON is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("training value must be JSON serializable", exception);
        }
    }
}
