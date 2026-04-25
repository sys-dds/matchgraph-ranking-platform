package com.matchgraph.api.serving;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.CandidateItem;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PreRankRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RecommendationSurfaceRepository servingRepository;

    public PreRankRepository(JdbcTemplate jdbcTemplate, RecommendationSurfaceRepository servingRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.servingRepository = servingRepository;
    }

    public UUID createRun(UUID requestId, int sourceCandidateCount, int survivorCount, int limit, Map<String, Object> summary) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into pre_rank_runs (
                    id, request_id, source_candidate_count, survivor_count, limit_count, summary_json
                )
                values (?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            requestId,
            sourceCandidateCount,
            survivorCount,
            limit,
            servingRepository.toJson(summary)
        );
        return id;
    }

    public void insertItem(UUID runId, CandidateItem item, boolean survived) {
        jdbcTemplate.update(
            """
                insert into pre_rank_items (
                    id, run_id, candidate_profile_id, source_key, pre_rank_score,
                    pre_rank_reasons_json, survived, filtered_reason
                )
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
            UUID.randomUUID(),
            runId,
            item.candidateProfileId(),
            item.sourceKey(),
            item.score(),
            servingRepository.toJson(item.reasons()),
            survived,
            item.filteredReason()
        );
    }
}
