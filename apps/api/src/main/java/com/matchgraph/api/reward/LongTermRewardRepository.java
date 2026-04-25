package com.matchgraph.api.reward;

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
public class LongTermRewardRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LongTermRewardRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createRun(LongTermRewardRequest request, int delayedWindowHours, boolean includeNeutral, boolean updateTrainingLabels) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into long_term_reward_runs (
                    id, dataset_run_id, decision_log_id, delayed_window_hours,
                    include_neutral, update_training_labels, status
                )
                values (?, ?, ?, ?, ?, ?, 'RUNNING')
                """,
            id,
            request.datasetRunId(),
            request.decisionLogId(),
            delayedWindowHours,
            includeNeutral,
            updateTrainingLabels
        );
        return id;
    }

    public List<RewardFact> examplesForDataset(UUID datasetRunId) {
        return jdbcTemplate.query(
            """
                select id as training_example_id, profile_id, candidate_profile_id, shown_at
                from training_examples
                where dataset_run_id = ?
                order by created_at, position nulls last
                """,
            this::mapRewardFact,
            datasetRunId
        );
    }

    public List<RewardFact> examplesForDecision(UUID decisionLogId) {
        return jdbcTemplate.query(
            """
                select null::uuid as training_example_id, rdl.profile_id, rdi.candidate_profile_id, rdl.created_at as shown_at
                from ranking_decision_items rdi
                join ranking_decision_logs rdl on rdl.id = rdi.decision_log_id
                where rdi.decision_log_id = ?
                order by rdi.position
                """,
            this::mapRewardFact,
            decisionLogId
        );
    }

    public List<EventFact> events(RewardFact fact, int delayedWindowHours) {
        return jdbcTemplate.query(
            """
                select id, event_type, occurred_at
                from interaction_events
                where actor_profile_id = ?
                  and target_profile_id = ?
                  and occurred_at >= ?
                  and occurred_at <= (?::timestamptz + (? || ' hours')::interval)
                order by occurred_at
                """,
            (rs, rowNum) -> new EventFact(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getObject("occurred_at", OffsetDateTime.class)),
            fact.profileId(),
            fact.candidateProfileId(),
            fact.shownAt(),
            fact.shownAt(),
            delayedWindowHours
        );
    }

    public boolean activeMatch(RewardFact fact, int delayedWindowHours) {
        Integer count = jdbcTemplate.queryForObject(
            """
                select count(*)
                from matches
                where status = 'ACTIVE'
                  and ((profile_a_id = ? and profile_b_id = ?) or (profile_a_id = ? and profile_b_id = ?))
                  and created_at >= ?
                  and created_at <= (?::timestamptz + (? || ' hours')::interval)
                """,
            Integer.class,
            fact.profileId(),
            fact.candidateProfileId(),
            fact.candidateProfileId(),
            fact.profileId(),
            fact.shownAt(),
            fact.shownAt(),
            delayedWindowHours
        );
        return count != null && count > 0;
    }

    public void insertLabel(UUID runId, RewardFact fact, BigDecimal shortTermReward, BigDecimal longTermReward, BigDecimal finalReward, Map<String, Object> components) {
        jdbcTemplate.update(
            """
                insert into long_term_reward_labels (
                    id, run_id, training_example_id, profile_id, candidate_profile_id,
                    short_term_reward, long_term_reward, final_reward_value, reward_components_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            fact.trainingExampleId(),
            fact.profileId(),
            fact.candidateProfileId(),
            shortTermReward,
            longTermReward,
            finalReward,
            toJson(components)
        );
    }

    public void upsertTrainingLabel(UUID trainingExampleId, BigDecimal finalReward, int delayedWindowHours) {
        if (trainingExampleId == null) {
            return;
        }
        jdbcTemplate.update(
            """
                insert into training_labels (
                    id, training_example_id, label_type, label_value, label_window_hours, source
                )
                values (?, ?, 'LONG_TERM_REWARD', ?, ?, 'LONG_TERM_REWARD')
                """,
            UUID.randomUUID(),
            trainingExampleId,
            finalReward,
            delayedWindowHours
        );
    }

    public void insertResult(UUID runId, int exampleCount, int labelledCount, BigDecimal avgShort, BigDecimal avgLong, BigDecimal avgFinal, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                insert into long_term_reward_results (
                    id, run_id, example_count, labelled_count, average_short_term_reward,
                    average_long_term_reward, average_final_reward, summary_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            UUID.randomUUID(),
            runId,
            exampleCount,
            labelledCount,
            avgShort,
            avgLong,
            avgFinal,
            toJson(summary)
        );
    }

    public void completeRun(UUID runId, Map<String, Object> summary) {
        jdbcTemplate.update(
            """
                update long_term_reward_runs
                set status = 'COMPLETED', summary_json = ?::jsonb, completed_at = now()
                where id = ?
                """,
            toJson(summary),
            runId
        );
    }

    public Optional<LongTermRewardRun> findRun(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, dataset_run_id, decision_log_id, delayed_window_hours, include_neutral,
                    update_training_labels, status, summary_json::text as summary_json, created_at, completed_at
                from long_term_reward_runs
                where id = ?
                """,
            this::mapRunWithoutResult,
            runId
        ).stream().findFirst()
            .map(run -> new LongTermRewardRun(run.id(), run.datasetRunId(), run.decisionLogId(), run.delayedWindowHours(), run.includeNeutral(), run.updateTrainingLabels(), run.status(), run.summary(), run.createdAt(), run.completedAt(), result(run.id()).orElse(null)));
    }

    public Optional<LongTermRewardResult> result(UUID runId) {
        return jdbcTemplate.query(
            """
                select id, run_id, example_count, labelled_count, average_short_term_reward,
                    average_long_term_reward, average_final_reward, summary_json::text as summary_json, created_at
                from long_term_reward_results
                where run_id = ?
                """,
            this::mapResult,
            runId
        ).stream().findFirst();
    }

    public Map<String, Object> summary() {
        Integer runs = jdbcTemplate.queryForObject("select count(*) from long_term_reward_runs", Integer.class);
        BigDecimal avg = jdbcTemplate.queryForObject("select coalesce(avg(average_final_reward), 0) from long_term_reward_results", BigDecimal.class);
        return Map.of(
            "runCount", runs == null ? 0 : runs,
            "averageFinalReward", avg == null ? BigDecimal.ZERO : avg,
            "semantics", "Product-quality delayed reward proxy, not true user happiness.",
            "conversationStarted", "NOT_AVAILABLE unless actual conversation data exists"
        );
    }

    private RewardFact mapRewardFact(ResultSet rs, int rowNum) throws SQLException {
        return new RewardFact(
            rs.getObject("training_example_id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getObject("shown_at", OffsetDateTime.class)
        );
    }

    private LongTermRewardRun mapRunWithoutResult(ResultSet rs, int rowNum) throws SQLException {
        return new LongTermRewardRun(
            rs.getObject("id", UUID.class),
            rs.getObject("dataset_run_id", UUID.class),
            rs.getObject("decision_log_id", UUID.class),
            rs.getInt("delayed_window_hours"),
            rs.getBoolean("include_neutral"),
            rs.getBoolean("update_training_labels"),
            rs.getString("status"),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            null
        );
    }

    private LongTermRewardResult mapResult(ResultSet rs, int rowNum) throws SQLException {
        return new LongTermRewardResult(
            rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getInt("example_count"),
            rs.getInt("labelled_count"),
            rs.getBigDecimal("average_short_term_reward"),
            rs.getBigDecimal("average_long_term_reward"),
            rs.getBigDecimal("average_final_reward"),
            map(rs.getString("summary_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored reward JSON is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("reward value must be JSON serializable", exception);
        }
    }

    public record RewardFact(UUID trainingExampleId, UUID profileId, UUID candidateProfileId, OffsetDateTime shownAt) {
    }

    public record EventFact(UUID id, String type, OffsetDateTime occurredAt) {
    }
}
