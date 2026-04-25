package com.matchgraph.api.training;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
public class TrainingDatasetRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TrainingDatasetRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createRun(CreateTrainingDatasetRequest request, int labelWindowHours, Map<String, Object> config) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into training_dataset_runs (
                    id, dataset_key, source_window_start, source_window_end,
                    label_window_hours, status, config_json
                )
                values (?, ?, ?, ?, ?, 'RUNNING', ?::jsonb)
                """,
            id,
            request.datasetKey(),
            request.sourceWindowStart(),
            request.sourceWindowEnd(),
            labelWindowHours,
            toJson(config)
        );
        return id;
    }

    public void completeRun(UUID runId, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update training_dataset_runs
                set status = 'COMPLETED',
                    summary_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            toJson(summary),
            runId
        );
    }

    public List<SourceExampleFact> sourceExamples(CreateTrainingDatasetRequest request) {
        int limit = request.maxExamples() == null ? 5000 : Math.max(1, Math.min(50000, request.maxExamples()));
        return jdbcTemplate.query(
            """
                select rdl.profile_id,
                    rdi.candidate_profile_id,
                    rdl.id as decision_log_id,
                    fs.id as feed_snapshot_id,
                    fi.id as feed_item_id,
                    rdl.feature_snapshot_run_id,
                    rdi.feature_snapshot_id,
                    rdl.ranking_version,
                    coalesce(fi.position, rdi.position) as position,
                    coalesce(fi.created_at, rdl.created_at) as shown_at,
                    rdi.source_types_json::text as source_types_json
                from ranking_decision_items rdi
                join ranking_decision_logs rdl on rdl.id = rdi.decision_log_id
                left join feed_items fi on fi.ranking_decision_log_id = rdi.decision_log_id
                    and fi.candidate_profile_id = rdi.candidate_profile_id
                left join feed_snapshots fs on fs.id = fi.feed_snapshot_id
                where (?::timestamptz is null or rdl.created_at >= ?::timestamptz)
                  and (?::timestamptz is null or rdl.created_at <= ?::timestamptz)
                order by rdl.created_at desc, rdi.position
                limit ?
                """,
            (rs, rowNum) -> new SourceExampleFact(
                rs.getObject("profile_id", UUID.class),
                rs.getObject("candidate_profile_id", UUID.class),
                rs.getObject("decision_log_id", UUID.class),
                rs.getObject("feed_snapshot_id", UUID.class),
                rs.getObject("feed_item_id", UUID.class),
                rs.getObject("feature_snapshot_run_id", UUID.class),
                rs.getObject("feature_snapshot_id", UUID.class),
                rs.getString("ranking_version"),
                rs.getInt("position"),
                rs.getObject("shown_at", OffsetDateTime.class),
                list(rs.getString("source_types_json"))
            ),
            request.sourceWindowStart(),
            request.sourceWindowStart(),
            request.sourceWindowEnd(),
            request.sourceWindowEnd(),
            limit
        );
    }

    public FeaturePayload featurePayload(UUID featureSnapshotId) {
        List<FeatureValueFact> values = jdbcTemplate.query(
            """
                select feature_key, numeric_value, text_value, json_value::text as json_value, freshness_status
                from candidate_feature_values
                where snapshot_id = ?
                order by feature_key
                """,
            this::mapFeatureValue,
            featureSnapshotId
        );
        Map<String, Object> serving = new LinkedHashMap<>();
        Map<String, Object> offline = new LinkedHashMap<>();
        int missing = 0;
        int stale = 0;
        for (FeatureValueFact value : values) {
            Object decoded = value.value();
            serving.put(value.featureKey(), decoded);
            offline.put(value.featureKey(), decoded);
            if ("MISSING".equals(value.freshnessStatus())) {
                missing++;
            }
            if ("STALE".equals(value.freshnessStatus()) || value.featureKey().contains("embedding") && "STALE".equals(String.valueOf(decoded))) {
                stale++;
            }
        }
        offline.put("_offlineReconstructionSource", "immutable_candidate_feature_values");
        return new FeaturePayload(serving, offline, missing, stale);
    }

    public List<EventFact> labelEvents(SourceExampleFact fact, int labelWindowHours) {
        List<EventFact> events = new ArrayList<>(jdbcTemplate.query(
            """
                select id, event_type, occurred_at
                from interaction_events
                where actor_profile_id = ?
                  and target_profile_id = ?
                  and occurred_at >= ?
                  and occurred_at <= (?::timestamptz + (? || ' hours')::interval)
                order by occurred_at
                """,
            this::mapEventFact,
            fact.profileId(),
            fact.candidateProfileId(),
            fact.shownAt(),
            fact.shownAt(),
            labelWindowHours
        ));
        jdbcTemplate.query(
            """
                select id, created_at
                from matches
                where status = 'ACTIVE'
                  and ((profile_a_id = ? and profile_b_id = ?) or (profile_a_id = ? and profile_b_id = ?))
                  and created_at >= ?
                  and created_at <= (?::timestamptz + (? || ' hours')::interval)
                order by created_at
                """,
            (rs, rowNum) -> new EventFact(
                rs.getObject("id", UUID.class),
                "MATCH_CREATED",
                rs.getObject("created_at", OffsetDateTime.class)
            ),
            fact.profileId(),
            fact.candidateProfileId(),
            fact.candidateProfileId(),
            fact.profileId(),
            fact.shownAt(),
            fact.shownAt(),
            labelWindowHours
        ).forEach(events::add);
        return events;
    }

    public Optional<SyntheticLabelFact> syntheticLabel(UUID profileId, UUID candidateProfileId) {
        return jdbcTemplate.query(
            """
                select compatibility_label, expected_relevance
                from synthetic_ground_truth_labels
                where actor_profile_id = ?
                  and candidate_profile_id = ?
                order by created_at desc
                limit 1
                """,
            (rs, rowNum) -> new SyntheticLabelFact(rs.getString("compatibility_label"), rs.getBigDecimal("expected_relevance")),
            profileId,
            candidateProfileId
        ).stream().findFirst();
    }

    public PropensityFact propensity(SourceExampleFact fact) {
        return jdbcTemplate.query(
            """
                select position
                from candidate_exposure_events
                where (feed_item_id = ? or (? is null and decision_log_id = ? and candidate_profile_id = ?))
                order by exposure_timestamp desc
                limit 1
                """,
            (rs, rowNum) -> {
                int position = rs.getInt("position");
                double value = Math.max(0.01d, Math.min(1d, 1d / Math.sqrt(position + 1d)));
                return new PropensityFact(BigDecimal.valueOf(value).setScale(6, java.math.RoundingMode.HALF_UP), "POSITION_APPROX");
            },
            fact.feedItemId(),
            fact.feedItemId(),
            fact.decisionLogId(),
            fact.candidateProfileId()
        ).stream().findFirst().orElse(new PropensityFact(null, null));
    }

    public UUID insertExample(
        UUID datasetRunId,
        SourceExampleFact fact,
        FeaturePayload features,
        LabelStoreService.LabelOutcome label,
        int labelWindowHours,
        PropensityFact propensity
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into training_examples (
                    id, dataset_run_id, profile_id, candidate_profile_id, decision_log_id,
                    feed_snapshot_id, feed_item_id, feature_snapshot_run_id, feature_snapshot_id,
                    ranking_version, position, shown_at, source_types_json, serving_features_json,
                    offline_features_json, label_json, label_value, label_positive, label_negative,
                    label_neutral, label_window_hours, propensity, propensity_source
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                """,
            id,
            datasetRunId,
            fact.profileId(),
            fact.candidateProfileId(),
            fact.decisionLogId(),
            fact.feedSnapshotId(),
            fact.feedItemId(),
            fact.featureSnapshotRunId(),
            fact.featureSnapshotId(),
            fact.rankingVersion(),
            fact.position(),
            fact.shownAt(),
            toJson(fact.sourceTypes()),
            toJson(features.servingFeatures()),
            toJson(features.offlineFeatures()),
            toJson(label.labelJson()),
            label.value(),
            label.positive(),
            label.negative(),
            label.neutral(),
            labelWindowHours,
            propensity.propensity(),
            propensity.source()
        );
        return id;
    }

    public void insertLabel(UUID exampleId, LabelStoreService.LabelComponent component, int labelWindowHours) {
        jdbcTemplate.update(
            """
                insert into training_labels (
                    id, training_example_id, label_type, label_value, event_id, event_time,
                    label_window_hours, source
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            UUID.randomUUID(),
            exampleId,
            component.type(),
            component.value(),
            component.eventId(),
            component.eventTime(),
            labelWindowHours,
            component.source()
        );
    }

    public void insertQualityReport(UUID datasetRunId, QualityCounts counts) {
        jdbcTemplate.update(
            """
                insert into training_dataset_quality_reports (
                    id, dataset_run_id, example_count, labelled_count, positive_count, negative_count,
                    neutral_count, missing_feature_count, stale_embedding_count, propensity_coverage,
                    position_distribution_json, source_distribution_json, label_distribution_json, summary_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
                """,
            UUID.randomUUID(),
            datasetRunId,
            counts.exampleCount(),
            counts.labelledCount(),
            counts.positiveCount(),
            counts.negativeCount(),
            counts.neutralCount(),
            counts.missingFeatureCount(),
            counts.staleEmbeddingCount(),
            counts.propensityCoverage(),
            toJson(counts.positionDistribution()),
            toJson(counts.sourceDistribution()),
            toJson(counts.labelDistribution()),
            toJson(counts.summary())
        );
    }

    public Optional<TrainingDatasetRun> findRun(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, dataset_key, source_window_start, source_window_end, label_window_hours,
                    status, config_json::text as config_json, summary_json::text as summary_json,
                    created_at, completed_at
                from training_dataset_runs
                where id = ?
                """,
            this::mapRun,
            runId
        ).stream().findFirst();
    }

    public List<TrainingExample> examples(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, dataset_run_id, profile_id, candidate_profile_id, decision_log_id,
                    feed_snapshot_id, feed_item_id, feature_snapshot_run_id, feature_snapshot_id,
                    ranking_version, position, shown_at, source_types_json::text as source_types_json,
                    serving_features_json::text as serving_features_json,
                    offline_features_json::text as offline_features_json,
                    label_json::text as label_json, label_value, label_positive, label_negative,
                    label_neutral, label_window_hours, propensity, propensity_source, created_at
                from training_examples
                where dataset_run_id = ?
                order by created_at, position nulls last
                """,
            this::mapExample,
            runId
        );
    }

    public List<TrainingExample> examplesForDecision(UUID decisionLogId) {
        return jdbcTemplate.query(
            """
                select id, dataset_run_id, profile_id, candidate_profile_id, decision_log_id,
                    feed_snapshot_id, feed_item_id, feature_snapshot_run_id, feature_snapshot_id,
                    ranking_version, position, shown_at, source_types_json::text as source_types_json,
                    serving_features_json::text as serving_features_json,
                    offline_features_json::text as offline_features_json,
                    label_json::text as label_json, label_value, label_positive, label_negative,
                    label_neutral, label_window_hours, propensity, propensity_source, created_at
                from training_examples
                where decision_log_id = ?
                order by position nulls last, created_at
                """,
            this::mapExample,
            decisionLogId
        );
    }

    public Optional<TrainingDatasetQualityReport> quality(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, dataset_run_id, example_count, labelled_count, positive_count, negative_count,
                    neutral_count, missing_feature_count, stale_embedding_count, propensity_coverage,
                    position_distribution_json::text as position_distribution_json,
                    source_distribution_json::text as source_distribution_json,
                    label_distribution_json::text as label_distribution_json,
                    summary_json::text as summary_json, created_at
                from training_dataset_quality_reports
                where dataset_run_id = ?
                """,
            this::mapQuality,
            runId
        ).stream().findFirst();
    }

    private FeatureValueFact mapFeatureValue(ResultSet rs, int rowNum) throws SQLException {
        Object value = rs.getBigDecimal("numeric_value");
        if (value == null) {
            value = rs.getString("text_value");
        }
        if (value == null && rs.getString("json_value") != null) {
            value = object(rs.getString("json_value"));
        }
        return new FeatureValueFact(rs.getString("feature_key"), value, rs.getString("freshness_status"));
    }

    private EventFact mapEventFact(ResultSet rs, int rowNum) throws SQLException {
        return new EventFact(
            rs.getObject("id", UUID.class),
            rs.getString("event_type"),
            rs.getObject("occurred_at", OffsetDateTime.class)
        );
    }

    private TrainingDatasetRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new TrainingDatasetRun(
            rs.getObject("id", UUID.class),
            rs.getString("dataset_key"),
            rs.getObject("source_window_start", OffsetDateTime.class),
            rs.getObject("source_window_end", OffsetDateTime.class),
            rs.getInt("label_window_hours"),
            rs.getString("status"),
            map(rs.getString("config_json")),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class)
        );
    }

    private TrainingExample mapExample(ResultSet rs, int rowNum) throws SQLException {
        int position = rs.getInt("position");
        return new TrainingExample(
            rs.getObject("id", UUID.class),
            rs.getObject("dataset_run_id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getObject("decision_log_id", UUID.class),
            rs.getObject("feed_snapshot_id", UUID.class),
            rs.getObject("feed_item_id", UUID.class),
            rs.getObject("feature_snapshot_run_id", UUID.class),
            rs.getObject("feature_snapshot_id", UUID.class),
            rs.getString("ranking_version"),
            rs.wasNull() ? null : position,
            rs.getObject("shown_at", OffsetDateTime.class),
            list(rs.getString("source_types_json")),
            map(rs.getString("serving_features_json")),
            map(rs.getString("offline_features_json")),
            map(rs.getString("label_json")),
            rs.getBigDecimal("label_value"),
            rs.getBoolean("label_positive"),
            rs.getBoolean("label_negative"),
            rs.getBoolean("label_neutral"),
            rs.getInt("label_window_hours"),
            rs.getBigDecimal("propensity"),
            rs.getString("propensity_source"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private TrainingDatasetQualityReport mapQuality(ResultSet rs, int rowNum) throws SQLException {
        return new TrainingDatasetQualityReport(
            rs.getObject("id", UUID.class),
            rs.getObject("dataset_run_id", UUID.class),
            rs.getInt("example_count"),
            rs.getInt("labelled_count"),
            rs.getInt("positive_count"),
            rs.getInt("negative_count"),
            rs.getInt("neutral_count"),
            rs.getInt("missing_feature_count"),
            rs.getInt("stale_embedding_count"),
            rs.getBigDecimal("propensity_coverage"),
            map(rs.getString("position_distribution_json")),
            map(rs.getString("source_distribution_json")),
            map(rs.getString("label_distribution_json")),
            map(rs.getString("summary_json")),
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

    private Object object(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored feature JSON is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("training value must be JSON serializable", exception);
        }
    }

    public record SourceExampleFact(
        UUID profileId,
        UUID candidateProfileId,
        UUID decisionLogId,
        UUID feedSnapshotId,
        UUID feedItemId,
        UUID featureSnapshotRunId,
        UUID featureSnapshotId,
        String rankingVersion,
        int position,
        OffsetDateTime shownAt,
        List<String> sourceTypes
    ) {
    }

    public record FeatureValueFact(String featureKey, Object value, String freshnessStatus) {
    }

    public record FeaturePayload(
        Map<String, Object> servingFeatures,
        Map<String, Object> offlineFeatures,
        int missingFeatureCount,
        int staleEmbeddingCount
    ) {
    }

    public record EventFact(UUID eventId, String eventType, OffsetDateTime eventTime) {
    }

    public record SyntheticLabelFact(String compatibilityLabel, BigDecimal expectedRelevance) {
    }

    public record PropensityFact(BigDecimal propensity, String source) {
    }

    public record QualityCounts(
        int exampleCount,
        int labelledCount,
        int positiveCount,
        int negativeCount,
        int neutralCount,
        int missingFeatureCount,
        int staleEmbeddingCount,
        BigDecimal propensityCoverage,
        Map<String, Object> positionDistribution,
        Map<String, Object> sourceDistribution,
        Map<String, Object> labelDistribution,
        Map<String, Object> summary
    ) {
    }
}
