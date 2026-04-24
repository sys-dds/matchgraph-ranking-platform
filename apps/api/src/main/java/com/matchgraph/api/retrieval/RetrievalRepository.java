package com.matchgraph.api.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
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
                returning id, profile_id, status, requested_limit, final_candidate_count, exclusion_count,
                    source_coverage_json::text as source_coverage_json, created_at, completed_at
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
                    retrieval_run_id, candidate_profile_id, source_type, source_rank, excluded, exclusion_reason
                )
                values (?, ?, ?, ?, ?, ?)
                on conflict (retrieval_run_id, candidate_profile_id, source_type) do nothing
                """,
            runId,
            candidate.candidateProfileId(),
            candidate.sourceTypes().getFirst().name(),
            candidate.sourceRank(),
            candidate.excluded(),
            candidate.exclusionReason()
        );
    }

    public void completeRun(UUID runId, int finalCandidateCount, int exclusionCount, Map<CandidateSourceType, Integer> sourceCoverage) {
        jdbcTemplate.update(
            """
                update candidate_retrieval_runs
                set status = 'COMPLETED',
                    final_candidate_count = ?,
                    exclusion_count = ?,
                    source_coverage_json = ?::jsonb,
                    completed_at = now()
                where id = ?
                """,
            finalCandidateCount,
            exclusionCount,
            coverageJson(sourceCoverage),
            runId
        );
    }

    public Optional<CandidateRetrievalRun> findRun(UUID profileId, UUID runId) {
        List<CandidateRetrievalRun> runs = jdbcTemplate.query(
            """
                select id, profile_id, status, requested_limit, final_candidate_count, exclusion_count,
                    source_coverage_json::text as source_coverage_json, created_at, completed_at
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
                run.finalCandidateCount(),
                run.exclusionCount(),
                run.sourceCoverage(),
                run.createdAt(),
                run.completedAt(),
                mergedCandidates(run.id(), false)
            ));
    }

    public List<RetrievedCandidate> mergedCandidates(UUID runId, boolean includeExcluded) {
        List<RetrievedCandidate> raw = jdbcTemplate.query(
            """
                select candidate_profile_id, source_type, source_rank, excluded, exclusion_reason
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
                rs.getString("exclusion_reason")
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

    private CandidateRetrievalRun mapRunWithoutCandidates(ResultSet rs, int rowNum) throws SQLException {
        return new CandidateRetrievalRun(
            rs.getObject("id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getString("status"),
            rs.getInt("requested_limit"),
            rs.getInt("final_candidate_count"),
            rs.getInt("exclusion_count"),
            sourceCoverage(rs.getString("source_coverage_json")),
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

        private RetrievedCandidateAccumulator(RetrievedCandidate candidate) {
            this.profileId = candidate.candidateProfileId();
            this.sourceRank = candidate.sourceRank();
            this.excluded = candidate.excluded();
            this.exclusionReason = candidate.exclusionReason();
        }

        private void add(RetrievedCandidate candidate) {
            sourceTypes.addAll(candidate.sourceTypes());
            sourceRank = Math.min(sourceRank, candidate.sourceRank());
            excluded = excluded || candidate.excluded();
            if (exclusionReason == null) {
                exclusionReason = candidate.exclusionReason();
            }
        }

        private RetrievedCandidate toCandidate() {
            return new RetrievedCandidate(profileId, sourceTypes.stream().distinct().toList(), sourceRank, excluded, exclusionReason);
        }
    }
}
