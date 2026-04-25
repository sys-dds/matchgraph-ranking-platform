package com.matchgraph.api.serving;

import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.CandidateItem;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HeavyRankRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RecommendationSurfaceRepository servingRepository;

    public HeavyRankRepository(JdbcTemplate jdbcTemplate, RecommendationSurfaceRepository servingRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.servingRepository = servingRepository;
    }

    public UUID createRun(UUID requestId, String rankingVersion, boolean modelBacked, String modelKey, String versionKey, int timeoutBudgetMs, boolean fallbackUsed, String fallbackReason, int durationMs, Map<String, Object> summary) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into heavy_rank_runs (
                    id, request_id, ranking_version, model_backed, model_key, version_key,
                    timeout_budget_ms, fallback_used, fallback_reason, duration_ms, summary_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            requestId,
            rankingVersion,
            modelBacked,
            modelKey,
            versionKey,
            timeoutBudgetMs,
            fallbackUsed,
            fallbackReason,
            durationMs,
            servingRepository.toJson(summary)
        );
        return id;
    }

    public void insertItem(UUID runId, CandidateItem item, boolean modelBacked) {
        jdbcTemplate.update(
            """
                insert into heavy_rank_items (
                    id, run_id, candidate_profile_id, candidate_score, model_score, rule_score, score_reasons_json
                )
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            item.candidateProfileId(),
            item.score(),
            modelBacked ? item.score() : null,
            modelBacked ? null : item.score(),
            servingRepository.toJson(item.reasons())
        );
    }
}
