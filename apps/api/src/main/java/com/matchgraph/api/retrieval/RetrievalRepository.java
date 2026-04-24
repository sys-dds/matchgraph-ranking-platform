package com.matchgraph.api.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
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
public class RetrievalRepository {

    private static final TypeReference<Map<String, Integer>> COVERAGE_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> REASON_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RetrievalRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<RetrievedCandidate> recentlyActive(UUID profileId, int limit) {
        return sourceCandidates(
            """
                select id
                from profiles
                where id <> ?
                order by last_active_at desc nulls last, created_at desc
                limit ?
                """,
            CandidateSourceType.RECENTLY_ACTIVE,
            profileId,
            limit
        );
    }

    public List<RetrievedCandidate> sharedInterest(UUID profileId, int limit) {
        return sourceCandidates(
            """
                select candidate.profile_id as id
                from profile_interests actor
                join profile_interests candidate
                  on candidate.interest_key = actor.interest_key
                 and candidate.interest_value = actor.interest_value
                 and candidate.profile_id <> actor.profile_id
                where actor.profile_id = ?
                group by candidate.profile_id
                order by count(*) desc, candidate.profile_id
                limit ?
                """,
            CandidateSourceType.SHARED_INTEREST,
            profileId,
            limit
        );
    }

    public List<RetrievedCandidate> coldStart(UUID profileId, int limit) {
        return sourceCandidates(
            """
                select id
                from profiles
                where id <> ?
                order by created_at asc
                limit ?
                """,
            CandidateSourceType.COLD_START,
            profileId,
            limit
        );
    }

    public List<RetrievedCandidate> graphTwoHop(UUID profileId, int limit) {
        return graphSourceCandidates(
            """
                select distinct second.target_profile_id as id,
                    2::numeric as source_score,
                    jsonb_build_object('graphDistance', 2) as source_reason_json
                from profile_graph_edges first
                join profile_graph_edges second on second.source_profile_id = first.target_profile_id
                where first.source_profile_id = ?
                  and first.edge_type = 'FOLLOW'
                  and first.status = 'ACTIVE'
                  and second.edge_type = 'FOLLOW'
                  and second.status = 'ACTIVE'
                  and second.target_profile_id <> ?
                order by second.target_profile_id
                limit ?
                """,
            CandidateSourceType.GRAPH_TWO_HOP,
            profileId,
            limit
        );
    }

    public List<RetrievedCandidate> graphMutuals(UUID profileId, int limit) {
        return graphSourceCandidates(
            """
                select candidate.source_profile_id as id,
                    count(*)::numeric as source_score,
                    jsonb_build_object('mutualCount', count(*), 'commonNeighbourCount', count(*)) as source_reason_json
                from profile_graph_edges actor
                join profile_graph_edges candidate on candidate.target_profile_id = actor.target_profile_id
                where actor.source_profile_id = ?
                  and actor.edge_type = 'FOLLOW'
                  and actor.status = 'ACTIVE'
                  and candidate.edge_type = 'FOLLOW'
                  and candidate.status = 'ACTIVE'
                  and candidate.source_profile_id <> ?
                group by candidate.source_profile_id
                order by count(*) desc, candidate.source_profile_id
                limit ?
                """,
            CandidateSourceType.GRAPH_MUTUALS,
            profileId,
            limit
        );
    }

    public List<RetrievedCandidate> weakTieExploration(UUID profileId, int limit) {
        return graphSourceCandidates(
            """
                select distinct target_profile_id as id,
                    1::numeric as source_score,
                    jsonb_build_object('weakTieReason', edge_type) as source_reason_json
                from profile_graph_edges
                where source_profile_id = ?
                  and edge_type in ('FOLLOW', 'MUTE', 'REPORT')
                  and status = 'INACTIVE'
                  and target_profile_id <> ?
                order by target_profile_id
                limit ?
                """,
            CandidateSourceType.WEAK_TIE_EXPLORATION,
            profileId,
            limit
        );
    }

    public List<RetrievedCandidate> vectorSimilarity(UUID profileId, int limit) {
        return jdbcTemplate.query(
            """
                with source_embedding as (
                    select e.embedding, v.version_name
                    from profiles p
                    join profile_embeddings e on e.profile_id = p.id and e.is_active
                    join profile_embedding_versions v on v.id = e.embedding_version_id
                    where p.id = ?
                      and p.embedding_status = 'CURRENT'
                )
                select candidate.id,
                    (candidate_embedding.embedding <=> source_embedding.embedding)::numeric as source_score,
                    jsonb_build_object(
                        'embeddingVersion', candidate_version.version_name,
                        'vectorDistance', candidate_embedding.embedding <=> source_embedding.embedding,
                        'stalePolicy', 'CURRENT_ONLY'
                    ) as source_reason_json
                from source_embedding
                join profile_embeddings candidate_embedding on candidate_embedding.is_active
                join profiles candidate on candidate.id = candidate_embedding.profile_id
                join profile_embedding_versions candidate_version on candidate_version.id = candidate_embedding.embedding_version_id
                where candidate.id <> ?
                  and candidate.embedding_status = 'CURRENT'
                order by candidate_embedding.embedding <=> source_embedding.embedding, candidate.id
                limit ?
                """,
            (rs, rowNum) -> RetrievedCandidate.sourced(
                rs.getObject("id", UUID.class),
                CandidateSourceType.VECTOR_SIMILARITY,
                rowNum + 1,
                rs.getBigDecimal("source_score"),
                sourceReason(rs.getString("source_reason_json"))
            ),
            profileId,
            profileId,
            limit
        );
    }

    public List<RetrievedCandidate> locationNearby(UUID profileId, int limit) {
        return jdbcTemplate.query(
            """
                with source_location as (
                    select approximate_point, city, region, country
                    from profile_locations
                    where profile_id = ?
                )
                select candidate.profile_id as id,
                    case
                        when source_location.approximate_point is not null and candidate.approximate_point is not null
                            then round((ST_Distance(candidate.approximate_point, source_location.approximate_point) / 1000.0)::numeric, 2)
                        else null
                    end as source_score,
                    jsonb_build_object(
                        'distanceBand',
                        case
                            when source_location.city is not null and lower(source_location.city) = lower(candidate.city) then 'SAME_CITY'
                            when source_location.approximate_point is not null and candidate.approximate_point is not null
                                and ST_DWithin(candidate.approximate_point, source_location.approximate_point, 10000) then 'NEARBY_10KM'
                            when source_location.approximate_point is not null and candidate.approximate_point is not null
                                and ST_DWithin(candidate.approximate_point, source_location.approximate_point, 50000) then 'NEARBY_50KM'
                            when source_location.region is not null and lower(source_location.region) = lower(candidate.region) then 'SAME_REGION'
                            when source_location.country is not null and lower(source_location.country) = lower(candidate.country) then 'SAME_COUNTRY'
                            when source_location.approximate_point is null or candidate.approximate_point is null then 'UNKNOWN'
                            else 'FAR'
                        end,
                        'approximateDistanceKm',
                        case
                            when source_location.approximate_point is not null and candidate.approximate_point is not null
                                then round((ST_Distance(candidate.approximate_point, source_location.approximate_point) / 1000.0)::numeric, 1)
                            else null
                        end,
                        'locationMode',
                        case
                            when source_location.city is not null and lower(source_location.city) = lower(candidate.city) then 'SAME_CITY'
                            when source_location.region is not null and lower(source_location.region) = lower(candidate.region) then 'SAME_REGION'
                            when source_location.country is not null and lower(source_location.country) = lower(candidate.country) then 'SAME_COUNTRY'
                            else 'RADIUS'
                        end
                    ) as source_reason_json
                from source_location
                join profile_locations candidate on candidate.profile_id <> ?
                where (
                    source_location.approximate_point is not null
                    and candidate.approximate_point is not null
                    and ST_DWithin(candidate.approximate_point, source_location.approximate_point, 50000)
                )
                or (
                    source_location.city is not null
                    and lower(source_location.city) = lower(candidate.city)
                )
                or (
                    source_location.region is not null
                    and lower(source_location.region) = lower(candidate.region)
                )
                or (
                    source_location.country is not null
                    and lower(source_location.country) = lower(candidate.country)
                )
                order by source_score nulls last, candidate.profile_id
                limit ?
                """,
            (rs, rowNum) -> RetrievedCandidate.sourced(
                rs.getObject("id", UUID.class),
                CandidateSourceType.LOCATION_NEARBY,
                rowNum + 1,
                rs.getBigDecimal("source_score"),
                sourceReason(rs.getString("source_reason_json"))
            ),
            profileId,
            profileId,
            limit
        );
    }

    public String profileStatus(UUID profileId) {
        return jdbcTemplate.queryForObject(
            "select status from profiles where id = ?",
            String.class,
            profileId
        );
    }

    public boolean safetyBlocked(UUID profileId) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                select exists (
                    select 1
                    from profile_safety_states
                    where profile_id = ?
                      and safety_state = 'BLOCKED'
                )
                """,
            Boolean.class,
            profileId
        );
        return Boolean.TRUE.equals(exists);
    }

    public boolean activeOutgoingEdge(UUID sourceProfileId, UUID targetProfileId, String edgeType) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                select exists (
                    select 1
                    from profile_graph_edges
                    where source_profile_id = ?
                      and target_profile_id = ?
                      and edge_type = ?
                      and status = 'ACTIVE'
                )
                """,
            Boolean.class,
            sourceProfileId,
            targetProfileId,
            edgeType
        );
        return Boolean.TRUE.equals(exists);
    }

    public CandidateRetrievalRun createRun(UUID runId, UUID profileId, int requestedLimit) {
        return jdbcTemplate.queryForObject(
            """
                insert into candidate_retrieval_runs (id, profile_id, status, requested_limit)
                values (?, ?, 'RUNNING', ?)
                returning id, profile_id, status, requested_limit, raw_candidate_count, deduped_candidate_count,
                    final_candidate_count, exclusion_count, exclusion_counts_json::text as exclusion_counts_json,
                    source_coverage_json::text as source_coverage_json, source_budgets_json::text as source_budgets_json,
                    retrieval_quality_json::text as retrieval_quality_json, created_at, completed_at
                """,
            this::mapRunWithoutCandidates,
            runId,
            profileId,
            requestedLimit
        );
    }

    public void insertItem(UUID runId, RetrievedCandidate candidate) {
        jdbcTemplate.update(
            """
                insert into candidate_retrieval_items (
                    retrieval_run_id, candidate_profile_id, source_type, source_rank, excluded, exclusion_reason,
                    source_score, source_reason_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                on conflict (retrieval_run_id, candidate_profile_id, source_type) do nothing
                """,
            runId,
            candidate.candidateProfileId(),
            candidate.sourceTypes().getFirst().name(),
            candidate.sourceRank(),
            candidate.excluded(),
            candidate.exclusionReason(),
            candidate.sourceScore(),
            reasonJson(candidate.sourceReason())
        );
    }

    public void completeRun(
        UUID runId,
        int rawCandidateCount,
        int dedupedCandidateCount,
        int finalCandidateCount,
        int exclusionCount,
        Map<String, Integer> exclusionCounts,
        Map<CandidateSourceType, Integer> sourceCoverage,
        Map<CandidateSourceType, Integer> sourceBudgets,
        Map<String, Object> retrievalQuality
    ) {
        jdbcTemplate.update(
            """
                update candidate_retrieval_runs
                set status = 'COMPLETED',
                    raw_candidate_count = ?,
                    deduped_candidate_count = ?,
                    final_candidate_count = ?,
                    exclusion_count = ?,
                    exclusion_counts_json = ?::jsonb,
                    source_coverage_json = ?::jsonb,
                    source_budgets_json = ?::jsonb,
                    retrieval_quality_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            rawCandidateCount,
            dedupedCandidateCount,
            finalCandidateCount,
            exclusionCount,
            stringIntJson(exclusionCounts),
            coverageJson(sourceCoverage),
            coverageJson(sourceBudgets),
            objectJson(retrievalQuality),
            runId
        );
    }

    public Optional<CandidateRetrievalRun> findRun(UUID profileId, UUID runId) {
        return findRun(profileId, runId, false);
    }

    public Optional<CandidateRetrievalRun> findRun(UUID profileId, UUID runId, boolean includeExcluded) {
        List<CandidateRetrievalRun> runs = jdbcTemplate.query(
            """
                select id, profile_id, status, requested_limit, raw_candidate_count, deduped_candidate_count,
                    final_candidate_count, exclusion_count, exclusion_counts_json::text as exclusion_counts_json,
                    source_coverage_json::text as source_coverage_json, source_budgets_json::text as source_budgets_json,
                    retrieval_quality_json::text as retrieval_quality_json, created_at, completed_at
                from candidate_retrieval_runs
                where id = ?
                  and profile_id = ?
                """,
            this::mapRunWithoutCandidates,
            runId,
            profileId
        );
        return runs.stream().findFirst()
            .map(run -> new CandidateRetrievalRun(
                run.id(),
                run.profileId(),
                run.status(),
                run.requestedLimit(),
                run.rawCandidateCount(),
                run.dedupedCandidateCount(),
                run.finalCandidateCount(),
                run.exclusionCount(),
                run.exclusionCounts(),
                run.sourceCoverage(),
                run.sourceBudgets(),
                run.retrievalQuality(),
                run.createdAt(),
                run.completedAt(),
                mergedCandidates(run.id(), includeExcluded)
                    .stream()
                    .limit(run.requestedLimit())
                    .toList()
            ));
    }

    public List<RetrievedCandidate> mergedCandidates(UUID runId, boolean includeExcluded) {
        List<RetrievedCandidate> raw = jdbcTemplate.query(
            """
                select candidate_profile_id, source_type, source_rank, excluded, exclusion_reason,
                    source_score, source_reason_json::text as source_reason_json
                from candidate_retrieval_items
                where retrieval_run_id = ?
                  and (? or not excluded)
                order by excluded, source_rank, candidate_profile_id, source_type
                """,
            (rs, rowNum) -> new RetrievedCandidate(
                rs.getObject("candidate_profile_id", UUID.class),
                List.of(CandidateSourceType.valueOf(rs.getString("source_type"))),
                rs.getInt("source_rank"),
                rs.getBoolean("excluded"),
                rs.getString("exclusion_reason"),
                rs.getBigDecimal("source_score"),
                sourceReason(rs.getString("source_reason_json"))
            ),
            runId,
            includeExcluded
        );
        Map<UUID, RetrievedCandidateAccumulator> merged = new LinkedHashMap<>();
        for (RetrievedCandidate candidate : raw) {
            merged.computeIfAbsent(candidate.candidateProfileId(), ignored -> new RetrievedCandidateAccumulator(candidate))
                .add(candidate);
        }
        return merged.values().stream()
            .map(RetrievedCandidateAccumulator::toCandidate)
            .toList();
    }

    private List<RetrievedCandidate> sourceCandidates(String sql, CandidateSourceType sourceType, UUID profileId, int limit) {
        List<UUID> ids = jdbcTemplate.queryForList(sql, UUID.class, profileId, limit);
        List<RetrievedCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            candidates.add(RetrievedCandidate.sourced(ids.get(index), sourceType, index + 1));
        }
        return candidates;
    }

    private List<RetrievedCandidate> graphSourceCandidates(String sql, CandidateSourceType sourceType, UUID profileId, int limit) {
        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> RetrievedCandidate.sourced(
                rs.getObject("id", UUID.class),
                sourceType,
                rowNum + 1,
                rs.getBigDecimal("source_score"),
                sourceReason(rs.getString("source_reason_json"))
            ),
            profileId,
            profileId,
            limit
        );
    }

    private CandidateRetrievalRun mapRunWithoutCandidates(ResultSet rs, int rowNum) throws SQLException {
        return new CandidateRetrievalRun(
            rs.getObject("id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getString("status"),
            rs.getInt("requested_limit"),
            rs.getInt("raw_candidate_count"),
            rs.getInt("deduped_candidate_count"),
            rs.getInt("final_candidate_count"),
            rs.getInt("exclusion_count"),
            stringIntMap(rs.getString("exclusion_counts_json")),
            sourceCoverage(rs.getString("source_coverage_json")),
            sourceCoverage(rs.getString("source_budgets_json")),
            objectMap(rs.getString("retrieval_quality_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            List.of()
        );
    }

    private String coverageJson(Map<CandidateSourceType, Integer> coverage) {
        Map<String, Integer> json = new LinkedHashMap<>();
        coverage.forEach((sourceType, count) -> json.put(sourceType.name(), count));
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("source coverage must be JSON serializable", exception);
        }
    }

    private String stringIntJson(Map<String, Integer> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("string integer map must be JSON serializable", exception);
        }
    }

    private Map<String, Integer> stringIntMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, COVERAGE_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored string integer JSON is invalid", exception);
        }
    }

    private String objectJson(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("object map must be JSON serializable", exception);
        }
    }

    private Map<String, Object> objectMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, OBJECT_MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored object JSON is invalid", exception);
        }
    }

    private String reasonJson(Map<String, Object> reason) {
        try {
            return objectMapper.writeValueAsString(reason == null ? Map.of() : reason);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("source reason must be JSON serializable", exception);
        }
    }

    private Map<String, Object> sourceReason(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, REASON_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored source_reason_json is invalid", exception);
        }
    }

    private Map<CandidateSourceType, Integer> sourceCoverage(String json) {
        try {
            Map<String, Integer> raw = objectMapper.readValue(json, COVERAGE_TYPE);
            Map<CandidateSourceType, Integer> coverage = new EnumMap<>(CandidateSourceType.class);
            raw.forEach((sourceType, count) -> coverage.put(CandidateSourceType.valueOf(sourceType), count));
            return coverage;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored source_coverage_json is invalid", exception);
        }
    }

    private static final class RetrievedCandidateAccumulator {
        private final UUID profileId;
        private final List<CandidateSourceType> sourceTypes = new ArrayList<>();
        private int sourceRank;
        private boolean excluded;
        private String exclusionReason;
        private BigDecimal sourceScore;
        private final List<Map<String, Object>> sourceReasons = new ArrayList<>();

        private RetrievedCandidateAccumulator(RetrievedCandidate candidate) {
            this.profileId = candidate.candidateProfileId();
            this.sourceRank = candidate.sourceRank();
            this.excluded = candidate.excluded();
            this.exclusionReason = candidate.exclusionReason();
            this.sourceScore = candidate.sourceScore();
        }

        private void add(RetrievedCandidate candidate) {
            sourceTypes.addAll(candidate.sourceTypes());
            sourceRank = Math.min(sourceRank, candidate.sourceRank());
            excluded = excluded || candidate.excluded();
            if (exclusionReason == null) {
                exclusionReason = candidate.exclusionReason();
            }
            if (sourceScore == null) {
                sourceScore = candidate.sourceScore();
            }
            if (candidate.sourceReason() != null && !candidate.sourceReason().isEmpty()) {
                sourceReasons.add(candidate.sourceReason());
            }
        }

        private RetrievedCandidate toCandidate() {
            return new RetrievedCandidate(
                profileId,
                sourceTypes.stream().distinct().toList(),
                sourceRank,
                excluded,
                exclusionReason,
                sourceScore,
                Map.of("sourceReasons", sourceReasons)
            );
        }
    }
}
