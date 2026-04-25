package com.matchgraph.api.serving;

import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.serving.ServingModels.ServedItem;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SlateOptimizationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RecommendationSurfaceRepository servingRepository;

    public SlateOptimizationRepository(JdbcTemplate jdbcTemplate, RecommendationSurfaceRepository servingRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.servingRepository = servingRepository;
    }

    public UUID createRun(UUID requestId, Map<String, Object> constraints, boolean partial, String warning) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into slate_optimization_runs (id, request_id, constraints_json, partial_result, warning)
                values (?, ?, ?::jsonb, ?, ?)
                """,
            id,
            requestId,
            servingRepository.toJson(constraints),
            partial,
            warning
        );
        return id;
    }

    public void insertSelected(UUID runId, ServedItem item, int originalPosition) {
        jdbcTemplate.update(
            """
                insert into slate_optimization_items (
                    id, run_id, candidate_profile_id, source_key, original_position, optimized_position,
                    selected, constraint_reasons_json, dropped_reason
                )
                values (?, ?, ?, ?, ?, ?, true, ?::jsonb, null)
                """,
            UUID.randomUUID(),
            runId,
            item.candidateProfileId(),
            item.sourceTypes().isEmpty() ? null : item.sourceTypes().getFirst(),
            originalPosition,
            item.position(),
            servingRepository.toJson(item.reasons())
        );
    }

    public void insertDropped(UUID runId, CandidateItem item, int originalPosition) {
        jdbcTemplate.update(
            """
                insert into slate_optimization_items (
                    id, run_id, candidate_profile_id, source_key, original_position,
                    selected, constraint_reasons_json, dropped_reason
                )
                values (?, ?, ?, ?, ?, false, ?::jsonb, ?)
                """,
            UUID.randomUUID(),
            runId,
            item.candidateProfileId(),
            item.sourceKey(),
            originalPosition,
            servingRepository.toJson(item.reasons()),
            item.filteredReason()
        );
    }
}
