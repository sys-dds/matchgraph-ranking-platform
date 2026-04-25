package com.matchgraph.api.ltr;

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
public class LtrModelRegistryRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LtrModelRegistryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createModel(String modelKey, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into ltr_models (id, model_key, name, status)
                values (?, ?, ?, 'DRAFT')
                """,
            id,
            modelKey,
            name
        );
        return id;
    }

    public UUID createVersion(UUID modelId, String modelKey, CreateLtrModelVersionRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into ltr_model_versions (
                    id, model_id, model_key, version_key, model_type, status,
                    feature_schema_version, training_dataset_run_id, eligibility_json
                )
                values (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?::jsonb)
                """,
            id,
            modelId,
            modelKey,
            request.versionKey(),
            request.modelType(),
            request.featureSchemaVersion(),
            request.trainingDatasetRunId(),
            toJson(request.eligibility() == null ? Map.of() : request.eligibility())
        );
        insertTransition(id, null, "DRAFT", "version created", Map.of());
        return id;
    }

    public void insertFeatureSchema(UUID modelId, String modelKey, String schemaVersion, List<String> featureNames, Map<String, Object> schema) {
        jdbcTemplate.update(
            """
                insert into ltr_model_feature_schemas (
                    id, model_id, model_key, feature_schema_version, feature_names_json, schema_json
                )
                values (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                on conflict (model_key, feature_schema_version)
                do update set feature_names_json = excluded.feature_names_json,
                    schema_json = excluded.schema_json
                """,
            UUID.randomUUID(),
            modelId,
            modelKey,
            schemaVersion,
            toJson(featureNames),
            toJson(schema)
        );
    }

    public void updateVersionStatus(UUID versionId, String status) {
        jdbcTemplate.update(
            """
                update ltr_model_versions
                set status = ?,
                    activated_at = case when ? = 'ACTIVE' then now() else activated_at end
                where id = ?
                """,
            status,
            status,
            versionId
        );
    }

    public void linkTraining(UUID versionId, UUID trainingRunId, UUID datasetRunId, Map<String, Object> metrics) {
        jdbcTemplate.update(
            """
                update ltr_model_versions
                set training_run_id = ?,
                    training_dataset_run_id = ?,
                    metrics_json = ?::jsonb
                where id = ?
                """,
            trainingRunId,
            datasetRunId,
            toJson(metrics),
            versionId
        );
    }

    public void retireActiveVersions(String modelKey, UUID exceptVersionId) {
        jdbcTemplate.update(
            """
                update ltr_model_versions
                set status = 'RETIRED'
                where model_key = ?
                  and status = 'ACTIVE'
                  and id <> ?
                """,
            modelKey,
            exceptVersionId
        );
    }

    public void insertTransition(UUID versionId, String fromStatus, String toStatus, String reason, Map<String, Object> metadata) {
        jdbcTemplate.update(
            """
                insert into ltr_model_state_transitions (
                    id, model_version_id, from_status, to_status, reason, metadata_json
                )
                values (?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            versionId,
            fromStatus,
            toStatus,
            reason,
            toJson(metadata == null ? Map.of() : metadata)
        );
    }

    public void upsertArtifact(UUID modelVersionId, Map<String, Object> weights, List<String> featureNames, Map<String, Object> normalization, Map<String, Object> metadata) {
        jdbcTemplate.update(
            """
                insert into ltr_model_artifacts (
                    id, model_version_id, weights_json, feature_names_json, normalization_json, metadata_json
                )
                values (?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
                on conflict (model_version_id)
                do update set weights_json = excluded.weights_json,
                    feature_names_json = excluded.feature_names_json,
                    normalization_json = excluded.normalization_json,
                    metadata_json = excluded.metadata_json
                """,
            UUID.randomUUID(),
            modelVersionId,
            toJson(weights),
            toJson(featureNames),
            toJson(normalization),
            toJson(metadata)
        );
    }

    public Optional<LtrModel> findModel(String modelKey) {
        return jdbcTemplate.query(
            """
                select id, model_key, name, status, created_at, updated_at
                from ltr_models
                where model_key = ?
                """,
            this::mapModelWithoutVersions,
            modelKey
        ).stream().findFirst()
            .map(model -> new LtrModel(
                model.id(),
                model.modelKey(),
                model.name(),
                model.status(),
                model.createdAt(),
                model.updatedAt(),
                versions(model.modelKey())
            ));
    }

    public Optional<LtrModelVersion> findVersion(String modelKey, String versionKey) {
        return jdbcTemplate.query(
            """
                select id, model_id, model_key, version_key, model_type, status, feature_schema_version,
                    training_dataset_run_id, training_run_id, metrics_json::text as metrics_json,
                    eligibility_json::text as eligibility_json, created_at, activated_at
                from ltr_model_versions
                where model_key = ?
                  and version_key = ?
                """,
            this::mapVersion,
            modelKey,
            versionKey
        ).stream().findFirst();
    }

    public Optional<LtrModelVersion> findVersion(UUID versionId) {
        return jdbcTemplate.query(
            """
                select id, model_id, model_key, version_key, model_type, status, feature_schema_version,
                    training_dataset_run_id, training_run_id, metrics_json::text as metrics_json,
                    eligibility_json::text as eligibility_json, created_at, activated_at
                from ltr_model_versions
                where id = ?
                """,
            this::mapVersion,
            versionId
        ).stream().findFirst();
    }

    public List<LtrModelVersion> versions(String modelKey) {
        return jdbcTemplate.query(
            """
                select id, model_id, model_key, version_key, model_type, status, feature_schema_version,
                    training_dataset_run_id, training_run_id, metrics_json::text as metrics_json,
                    eligibility_json::text as eligibility_json, created_at, activated_at
                from ltr_model_versions
                where model_key = ?
                order by created_at
                """,
            this::mapVersion,
            modelKey
        );
    }

    public Optional<LtrModelArtifact> artifact(String modelKey, String versionKey) {
        return jdbcTemplate.query(
            """
                select a.id, a.model_version_id, a.weights_json::text as weights_json,
                    a.feature_names_json::text as feature_names_json,
                    a.normalization_json::text as normalization_json,
                    a.metadata_json::text as metadata_json, a.created_at
                from ltr_model_artifacts a
                join ltr_model_versions v on v.id = a.model_version_id
                where v.model_key = ?
                  and v.version_key = ?
                """,
            this::mapArtifact,
            modelKey,
            versionKey
        ).stream().findFirst();
    }

    public Optional<LtrFeatureSchema> featureSchema(String modelKey, String schemaVersion) {
        return jdbcTemplate.query(
            """
                select id, model_id, model_key, feature_schema_version,
                    feature_names_json::text as feature_names_json,
                    schema_json::text as schema_json, created_at
                from ltr_model_feature_schemas
                where model_key = ?
                  and feature_schema_version = ?
                """,
            this::mapFeatureSchema,
            modelKey,
            schemaVersion
        ).stream().findFirst();
    }

    private LtrModel mapModelWithoutVersions(ResultSet rs, int rowNum) throws SQLException {
        return new LtrModel(
            rs.getObject("id", UUID.class),
            rs.getString("model_key"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            List.of()
        );
    }

    private LtrModelVersion mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new LtrModelVersion(
            rs.getObject("id", UUID.class),
            rs.getObject("model_id", UUID.class),
            rs.getString("model_key"),
            rs.getString("version_key"),
            rs.getString("model_type"),
            rs.getString("status"),
            rs.getString("feature_schema_version"),
            rs.getObject("training_dataset_run_id", UUID.class),
            rs.getObject("training_run_id", UUID.class),
            map(rs.getString("metrics_json")),
            map(rs.getString("eligibility_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("activated_at", OffsetDateTime.class)
        );
    }

    private LtrModelArtifact mapArtifact(ResultSet rs, int rowNum) throws SQLException {
        return new LtrModelArtifact(
            rs.getObject("id", UUID.class),
            rs.getObject("model_version_id", UUID.class),
            map(rs.getString("weights_json")),
            list(rs.getString("feature_names_json")),
            map(rs.getString("normalization_json")),
            map(rs.getString("metadata_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private LtrFeatureSchema mapFeatureSchema(ResultSet rs, int rowNum) throws SQLException {
        return new LtrFeatureSchema(
            rs.getObject("id", UUID.class),
            rs.getObject("model_id", UUID.class),
            rs.getString("model_key"),
            rs.getString("feature_schema_version"),
            list(rs.getString("feature_names_json")),
            map(rs.getString("schema_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored LTR JSON is invalid", exception);
        }
    }

    private List<String> list(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored LTR list JSON is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("LTR value must be JSON serializable", exception);
        }
    }
}
