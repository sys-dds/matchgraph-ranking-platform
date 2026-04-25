package com.matchgraph.api.scale;

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
public class ScaleRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ScaleRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ScaleSeedRun createSeedRun(UUID id, ScaleSeedRequest request, int profileCount, int edgeCount, int interactionCount, int clusters, long seed, boolean allowLarge) {
        return jdbcTemplate.queryForObject(
            """
                insert into scale_seed_runs (
                    id, random_seed, profile_count, edge_count, interaction_count, embedding_enabled,
                    location_enabled, interest_cluster_count, allow_large, status
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING')
                returning id, random_seed, profile_count, edge_count, interaction_count, embedding_enabled,
                    location_enabled, interest_cluster_count, allow_large, status, summary_json::text as summary_json,
                    created_at, completed_at
                """,
            this::mapSeedRun,
            id,
            seed,
            profileCount,
            edgeCount,
            interactionCount,
            Boolean.TRUE.equals(request.embeddingEnabled()),
            Boolean.TRUE.equals(request.locationEnabled()),
            clusters,
            allowLarge
        );
    }

    public void completeSeedRun(UUID id, Map<String, Object> summary) {
        jdbcTemplate.update("update scale_seed_runs set status = 'COMPLETED', summary_json = ?::jsonb, completed_at = now() where id = ?", toJson(summary), id);
    }

    public Optional<ScaleSeedRun> seedRun(UUID id) {
        return jdbcTemplate.query(
            """
                select id, random_seed, profile_count, edge_count, interaction_count, embedding_enabled,
                    location_enabled, interest_cluster_count, allow_large, status, summary_json::text as summary_json,
                    created_at, completed_at
                from scale_seed_runs where id = ?
                """,
            this::mapSeedRun,
            id
        ).stream().findFirst();
    }

    public UUID createProfile(String externalRef, String displayName, boolean stale) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into profiles (
                    id, external_ref, display_name, profile_type, status, bio, city, region, country,
                    profile_completeness_score, embedding_status
                )
                values (?, ?, ?, 'USER', 'ACTIVE', 'seeded profile', 'Seed City', 'Seed Region', 'GB', 0.8750, ?)
                """,
            id,
            externalRef,
            displayName,
            stale ? "STALE" : "CURRENT"
        );
        jdbcTemplate.update("insert into profile_safety_states (profile_id, safety_state, reason) values (?, ?, ?)", id, stale ? "LIMITED" : "UNREVIEWED", "scale seed");
        return id;
    }

    public void addInterest(UUID profileId, String cluster) {
        jdbcTemplate.update("insert into profile_interests (profile_id, interest_key, interest_value, weight) values (?, 'scale_cluster', ?, 1.0)", profileId, cluster);
    }

    public void addLocation(UUID profileId, double lat, double lon) {
        jdbcTemplate.update(
            """
                insert into profile_locations (profile_id, approximate_point, precision_km, city, region, country)
                values (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 25, 'Seed City', 'Seed Region', 'GB')
                """,
            profileId,
            lon,
            lat
        );
    }

    public void addEmbedding(UUID profileId, String versionName, String vectorLiteral) {
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into profile_embedding_versions (id, version_name, model_name, dimensions) values (?, ?, 'scale-model', 384) on conflict (version_name) do nothing",
            versionId,
            versionName
        );
        UUID existingVersionId = jdbcTemplate.queryForObject("select id from profile_embedding_versions where version_name = ?", UUID.class, versionName);
        jdbcTemplate.update("insert into profile_embeddings (profile_id, embedding_version_id, embedding, is_active) values (?, ?, ?::vector, true)", profileId, existingVersionId, vectorLiteral);
    }

    public void addEdge(UUID source, UUID target) {
        jdbcTemplate.update(
            "insert into profile_graph_edges (id, source_profile_id, target_profile_id, edge_type, status, strength, reason) values (?, ?, ?, 'FOLLOW', 'ACTIVE', 1.0, 'scale seed') on conflict do nothing",
            UUID.randomUUID(),
            source,
            target
        );
    }

    public void addInteraction(UUID actor, UUID target, int index) {
        jdbcTemplate.update(
            """
                insert into interaction_events (
                    id, client_event_id, actor_profile_id, target_profile_id, event_type, occurred_at, metadata_json
                )
                values (?, ?, ?, ?, ?, now(), '{}'::jsonb)
                """,
            UUID.randomUUID(),
            "scale-" + actor + "-" + index,
            actor,
            target,
            index % 3 == 0 ? "LIKE" : "PROFILE_VIEW"
        );
    }

    public List<UUID> sampleProfiles(int limit) {
        return jdbcTemplate.queryForList("select id from profiles where status = 'ACTIVE' order by created_at desc limit ?", UUID.class, limit);
    }

    public RankingBenchmarkRun createBenchmarkRun(UUID id, RankingBenchmarkRequest request, int sampleCount) {
        return jdbcTemplate.queryForObject(
            """
                insert into ranking_benchmark_runs (
                    id, seed_run_id, sample_profile_count, include_offline_evaluation, cache_enabled, status, request_json
                )
                values (?, ?, ?, ?, ?, 'RUNNING', ?::jsonb)
                returning id, seed_run_id, sample_profile_count, include_offline_evaluation, cache_enabled,
                    status, request_json::text as request_json, created_at, completed_at
                """,
            this::mapBenchmarkRun,
            id,
            request.seedRunId(),
            sampleCount,
            Boolean.TRUE.equals(request.includeOfflineEvaluation()),
            Boolean.TRUE.equals(request.cacheEnabled()),
            toJson(Map.of("sampleProfileCount", sampleCount))
        );
    }

    public void completeBenchmarkRun(UUID id) {
        jdbcTemplate.update("update ranking_benchmark_runs set status = 'COMPLETED', completed_at = now() where id = ?", id);
    }

    public void insertBenchmarkResult(UUID runId, UUID profileId, long retrievalMs, long snapshotMs, long rankingMs, long feedMs, int candidates) {
        jdbcTemplate.update(
            """
                insert into ranking_benchmark_results (
                    id, benchmark_run_id, profile_id, retrieval_latency_ms, snapshot_latency_ms,
                    ranking_latency_ms, feed_latency_ms, candidate_count, result_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            profileId,
            retrievalMs,
            snapshotMs,
            rankingMs,
            feedMs,
            candidates
        );
    }

    public Optional<RankingBenchmarkRun> benchmarkRun(UUID id) {
        return jdbcTemplate.query(
            """
                select id, seed_run_id, sample_profile_count, include_offline_evaluation, cache_enabled,
                    status, request_json::text as request_json, created_at, completed_at
                from ranking_benchmark_runs where id = ?
                """,
            this::mapBenchmarkRun,
            id
        ).stream().findFirst();
    }

    public List<RankingBenchmarkResult> benchmarkResults(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, benchmark_run_id, profile_id, retrieval_latency_ms, snapshot_latency_ms,
                    ranking_latency_ms, feed_latency_ms, evaluation_latency_ms, candidate_count,
                    cache_hit_count, cache_miss_count, result_json::text as result_json, created_at
                from ranking_benchmark_results where benchmark_run_id = ?
                """,
            this::mapBenchmarkResult,
            runId
        );
    }

    private ScaleSeedRun mapSeedRun(ResultSet rs, int rowNum) throws SQLException {
        return new ScaleSeedRun(
            rs.getObject("id", UUID.class),
            rs.getLong("random_seed"),
            rs.getInt("profile_count"),
            rs.getInt("edge_count"),
            rs.getInt("interaction_count"),
            rs.getBoolean("embedding_enabled"),
            rs.getBoolean("location_enabled"),
            rs.getInt("interest_cluster_count"),
            rs.getBoolean("allow_large"),
            rs.getString("status"),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class)
        );
    }

    private RankingBenchmarkRun mapBenchmarkRun(ResultSet rs, int rowNum) throws SQLException {
        return new RankingBenchmarkRun(
            rs.getObject("id", UUID.class),
            rs.getObject("seed_run_id", UUID.class),
            rs.getInt("sample_profile_count"),
            rs.getBoolean("include_offline_evaluation"),
            rs.getBoolean("cache_enabled"),
            rs.getString("status"),
            map(rs.getString("request_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class)
        );
    }

    private RankingBenchmarkResult mapBenchmarkResult(ResultSet rs, int rowNum) throws SQLException {
        return new RankingBenchmarkResult(
            rs.getObject("id", UUID.class),
            rs.getObject("benchmark_run_id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getLong("retrieval_latency_ms"),
            rs.getLong("snapshot_latency_ms"),
            rs.getLong("ranking_latency_ms"),
            rs.getLong("feed_latency_ms"),
            (Long) rs.getObject("evaluation_latency_ms"),
            rs.getInt("candidate_count"),
            rs.getInt("cache_hit_count"),
            rs.getInt("cache_miss_count"),
            map(rs.getString("result_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("scale value must be JSON serializable", exception);
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored scale json is invalid", exception);
        }
    }
}
