package com.matchgraph.api.features;

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
public class FeatureSnapshotRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FeatureSnapshotRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createRun(UUID profileId, UUID retrievalRunId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into feature_snapshot_runs (
                    id, profile_id, retrieval_run_id, status, candidate_count,
                    stale_feature_count, missing_required_feature_count
                )
                values (?, ?, ?, 'RUNNING', 0, 0, 0)
                """,
            id,
            profileId,
            retrievalRunId
        );
        return id;
    }

    public void completeRun(UUID snapshotRunId, int candidateCount, int staleFeatureCount, int missingRequiredFeatureCount) {
        jdbcTemplate.update(
            """
                update feature_snapshot_runs
                set status = 'COMPLETED',
                    candidate_count = ?,
                    stale_feature_count = ?,
                    missing_required_feature_count = ?,
                    completed_at = now()
                where id = ?
                """,
            candidateCount,
            staleFeatureCount,
            missingRequiredFeatureCount,
            snapshotRunId
        );
    }

    public UUID createCandidateSnapshot(UUID snapshotRunId, UUID candidateProfileId, UUID retrievalRunId, List<String> sourceTypes, String freshnessStatus) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into candidate_feature_snapshots (
                    id, snapshot_run_id, candidate_profile_id, retrieval_run_id, source_types_json, feature_freshness_status
                )
                values (?, ?, ?, ?, ?::jsonb, ?)
                """,
            id,
            snapshotRunId,
            candidateProfileId,
            retrievalRunId,
            toJson(sourceTypes),
            freshnessStatus
        );
        return id;
    }

    public void insertValue(UUID snapshotId, CandidateFeatureValue value) {
        jdbcTemplate.update(
            """
                insert into candidate_feature_values (
                    snapshot_id, feature_key, numeric_value, text_value, json_value, freshness_status
                )
                values (?, ?, ?, ?, ?::jsonb, ?)
                """,
            snapshotId,
            value.featureKey(),
            value.numericValue(),
            value.textValue(),
            value.jsonValue() == null ? null : toJson(value.jsonValue()),
            value.freshnessStatus()
        );
    }

    public Optional<FeatureSnapshotRun> findRun(UUID profileId, UUID snapshotRunId) {
        List<FeatureSnapshotRun> runs = jdbcTemplate.query(
            """
                select id, profile_id, retrieval_run_id, status, candidate_count,
                    stale_feature_count, missing_required_feature_count, created_at, completed_at
                from feature_snapshot_runs
                where id = ?
                  and profile_id = ?
                """,
            this::mapRunWithoutCandidates,
            snapshotRunId,
            profileId
        );
        return runs.stream().findFirst()
            .map(run -> new FeatureSnapshotRun(
                run.id(),
                run.profileId(),
                run.retrievalRunId(),
                run.status(),
                run.candidateCount(),
                run.staleFeatureCount(),
                run.missingRequiredFeatureCount(),
                run.createdAt(),
                run.completedAt(),
                findCandidateSnapshots(run.id())
            ));
    }

    public List<CandidateFeatureSnapshot> findCandidateSnapshots(UUID snapshotRunId) {
        return jdbcTemplate.query(
            """
                select id, snapshot_run_id, candidate_profile_id, retrieval_run_id,
                    source_types_json::text as source_types_json, feature_freshness_status, created_at
                from candidate_feature_snapshots
                where snapshot_run_id = ?
                order by created_at, candidate_profile_id
                """,
            (rs, rowNum) -> mapCandidateSnapshot(rs),
            snapshotRunId
        );
    }

    public List<CandidateFeatureValue> findValues(UUID snapshotId) {
        return jdbcTemplate.query(
            """
                select feature_key, numeric_value, text_value, json_value::text as json_value, freshness_status, created_at
                from candidate_feature_values
                where snapshot_id = ?
                order by feature_key
                """,
            this::mapValue,
            snapshotId
        );
    }

    public List<RetrievalCandidateFact> retrievalCandidates(UUID profileId, UUID retrievalRunId) {
        return jdbcTemplate.query(
            """
                select i.candidate_profile_id,
                    array_agg(i.source_type order by i.source_rank, i.source_type) as source_types,
                    jsonb_agg(jsonb_build_object(
                        'sourceType', i.source_type,
                        'sourceRank', i.source_rank,
                        'excluded', i.excluded,
                        'exclusionReason', i.exclusion_reason
                    ) order by i.source_rank, i.source_type)::text as source_reasons_json
                from candidate_retrieval_runs r
                join candidate_retrieval_items i on i.retrieval_run_id = r.id
                where r.id = ?
                  and r.profile_id = ?
                  and not i.excluded
                group by i.candidate_profile_id
                order by min(i.source_rank), i.candidate_profile_id
                """,
            (rs, rowNum) -> new RetrievalCandidateFact(
                rs.getObject("candidate_profile_id", UUID.class),
                List.of((String[]) rs.getArray("source_types").getArray()),
                jsonMap("items", rs.getString("source_reasons_json"))
            ),
            retrievalRunId,
            profileId
        );
    }

    public Optional<CandidateProfileFact> candidateProfile(UUID actorProfileId, UUID candidateProfileId) {
        List<CandidateProfileFact> facts = jdbcTemplate.query(
            """
                select p.id,
                    p.status,
                    p.city,
                    p.region,
                    p.country,
                    p.last_active_at,
                    p.profile_completeness_score,
                    p.embedding_status,
                    v.version_name as embedding_version,
                    s.safety_state,
                    s.updated_at as safety_updated_at,
                    l.city as location_city,
                    l.region as location_region,
                    l.country as location_country,
                    l.updated_at as location_updated_at,
                    case when actor_location.approximate_point is not null and candidate_location.approximate_point is not null
                        then ST_Distance(actor_location.approximate_point, candidate_location.approximate_point) / 1000.0
                        else null
                    end as approximate_distance_km
                from profiles p
                left join profile_embeddings e on e.profile_id = p.id and e.is_active
                left join profile_embedding_versions v on v.id = e.embedding_version_id
                left join profile_safety_states s on s.profile_id = p.id
                left join profile_locations l on l.profile_id = p.id
                left join profile_locations actor_location on actor_location.profile_id = ?
                left join profile_locations candidate_location on candidate_location.profile_id = p.id
                where p.id = ?
                """,
            this::mapCandidateProfile,
            actorProfileId,
            candidateProfileId
        );
        return facts.stream().findFirst();
    }

    public int sharedInterestCount(UUID actorProfileId, UUID candidateProfileId) {
        Integer count = jdbcTemplate.queryForObject(
            """
                select count(*)::int
                from profile_interests actor
                join profile_interests candidate
                  on candidate.interest_key = actor.interest_key
                 and candidate.interest_value = actor.interest_value
                where actor.profile_id = ?
                  and candidate.profile_id = ?
                """,
            Integer.class,
            actorProfileId,
            candidateProfileId
        );
        return count == null ? 0 : count;
    }

    public Optional<String> dominantInterest(UUID candidateProfileId) {
        List<String> interests = jdbcTemplate.queryForList(
            """
                select interest_key || '=' || interest_value
                from profile_interests
                where profile_id = ?
                order by weight desc, interest_key, interest_value
                limit 1
                """,
            String.class,
            candidateProfileId
        );
        return interests.stream().findFirst();
    }

    public GraphFact graphFact(UUID actorProfileId, UUID candidateProfileId) {
        Boolean direct = jdbcTemplate.queryForObject(
            """
                select exists (
                    select 1 from profile_graph_edges
                    where source_profile_id = ?
                      and target_profile_id = ?
                      and edge_type = 'FOLLOW'
                      and status = 'ACTIVE'
                )
                """,
            Boolean.class,
            actorProfileId,
            candidateProfileId
        );
        Integer mutualCount = jdbcTemplate.queryForObject(
            """
                select count(*)::int
                from profile_graph_edges actor_edge
                join profile_graph_edges candidate_edge
                  on candidate_edge.target_profile_id = actor_edge.target_profile_id
                 and candidate_edge.edge_type = 'FOLLOW'
                 and candidate_edge.status = 'ACTIVE'
                where actor_edge.source_profile_id = ?
                  and candidate_edge.source_profile_id = ?
                  and actor_edge.edge_type = 'FOLLOW'
                  and actor_edge.status = 'ACTIVE'
                """,
            Integer.class,
            actorProfileId,
            candidateProfileId
        );
        Integer commonNeighbourCount = jdbcTemplate.queryForObject(
            """
                select count(*)::int
                from profile_graph_edges actor_edge
                join profile_graph_edges neighbour_edge
                  on neighbour_edge.source_profile_id = actor_edge.target_profile_id
                 and neighbour_edge.target_profile_id = ?
                 and neighbour_edge.edge_type = 'FOLLOW'
                 and neighbour_edge.status = 'ACTIVE'
                where actor_edge.source_profile_id = ?
                  and actor_edge.edge_type = 'FOLLOW'
                  and actor_edge.status = 'ACTIVE'
                """,
            Integer.class,
            candidateProfileId,
            actorProfileId
        );
        BigDecimal graphDistance = Boolean.TRUE.equals(direct)
            ? BigDecimal.ONE
            : (commonNeighbourCount != null && commonNeighbourCount > 0 ? BigDecimal.valueOf(2) : null);
        return new GraphFact(graphDistance, mutualCount == null ? 0 : mutualCount, commonNeighbourCount == null ? 0 : commonNeighbourCount);
    }

    public BigDecimal vectorDistance(UUID actorProfileId, UUID candidateProfileId) {
        List<BigDecimal> distances = jdbcTemplate.queryForList(
            """
                select (actor.embedding <=> candidate.embedding)::numeric as distance
                from profile_embeddings actor
                join profile_embeddings candidate on candidate.profile_id = ?
                where actor.profile_id = ?
                  and actor.is_active
                  and candidate.is_active
                limit 1
                """,
            BigDecimal.class,
            candidateProfileId,
            actorProfileId
        );
        return distances.stream().findFirst().orElse(null);
    }

    private FeatureSnapshotRun mapRunWithoutCandidates(ResultSet rs, int rowNum) throws SQLException {
        return new FeatureSnapshotRun(
            rs.getObject("id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("retrieval_run_id", UUID.class),
            rs.getString("status"),
            rs.getInt("candidate_count"),
            rs.getInt("stale_feature_count"),
            rs.getInt("missing_required_feature_count"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            List.of()
        );
    }

    private CandidateFeatureSnapshot mapCandidateSnapshot(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new CandidateFeatureSnapshot(
            id,
            rs.getObject("snapshot_run_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getObject("retrieval_run_id", UUID.class),
            stringList(rs.getString("source_types_json")),
            rs.getString("feature_freshness_status"),
            rs.getObject("created_at", OffsetDateTime.class),
            findValues(id)
        );
    }

    private CandidateFeatureValue mapValue(ResultSet rs, int rowNum) throws SQLException {
        return new CandidateFeatureValue(
            rs.getString("feature_key"),
            rs.getBigDecimal("numeric_value"),
            rs.getString("text_value"),
            rs.getString("json_value") == null ? null : map(rs.getString("json_value")),
            rs.getString("freshness_status"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private CandidateProfileFact mapCandidateProfile(ResultSet rs, int rowNum) throws SQLException {
        return new CandidateProfileFact(
            rs.getObject("id", UUID.class),
            rs.getString("status"),
            rs.getString("city"),
            rs.getString("region"),
            rs.getString("country"),
            rs.getObject("last_active_at", OffsetDateTime.class),
            rs.getBigDecimal("profile_completeness_score"),
            rs.getString("embedding_status"),
            rs.getString("embedding_version"),
            rs.getString("safety_state"),
            rs.getObject("safety_updated_at", OffsetDateTime.class),
            rs.getString("location_city"),
            rs.getString("location_region"),
            rs.getString("location_country"),
            rs.getObject("location_updated_at", OffsetDateTime.class),
            rs.getBigDecimal("approximate_distance_km")
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("feature snapshot value must be JSON serializable", exception);
        }
    }

    private List<String> stringList(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored source_types_json is invalid", exception);
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored json_value is invalid", exception);
        }
    }

    private Map<String, Object> jsonMap(String key, String json) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        try {
            wrapper.put(key, objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            }));
            return wrapper;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored retrieval source reason json is invalid", exception);
        }
    }

    public record RetrievalCandidateFact(UUID candidateProfileId, List<String> sourceTypes, Map<String, Object> sourceReasons) {
    }

    public record CandidateProfileFact(
        UUID profileId,
        String status,
        String city,
        String region,
        String country,
        OffsetDateTime lastActiveAt,
        BigDecimal profileCompletenessScore,
        String embeddingStatus,
        String embeddingVersion,
        String safetyState,
        OffsetDateTime safetyUpdatedAt,
        String locationCity,
        String locationRegion,
        String locationCountry,
        OffsetDateTime locationUpdatedAt,
        BigDecimal approximateDistanceKm
    ) {
    }

    public record GraphFact(BigDecimal graphDistance, int mutualCount, int commonNeighbourCount) {
    }
}
