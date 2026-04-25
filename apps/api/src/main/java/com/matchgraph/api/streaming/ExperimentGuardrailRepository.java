package com.matchgraph.api.streaming;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.streaming.StreamingModels.ExperimentGuardrailDecision;
import com.matchgraph.api.streaming.StreamingModels.ExperimentGuardrailRun;
import com.matchgraph.api.streaming.StreamingModels.LiveQualityAnomaly;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExperimentGuardrailRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExperimentGuardrailRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ExperimentGuardrailRun saveRun(List<ExperimentGuardrailDecision> decisions, Map<String, Object> summary) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into experiment_guardrail_runs (id, status, summary_json) values (?, 'COMPLETED', ?::jsonb)",
            runId,
            json(summary)
        );
        for (ExperimentGuardrailDecision decision : decisions) {
            jdbcTemplate.update(
                """
                    insert into experiment_guardrail_decisions (
                        id, run_id, experiment_key, variant_key, guardrail_status, decision_action, reason_json,
                        paused_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?::jsonb, case when ? then now() else null end)
                    """,
                decision.id(),
                runId,
                decision.experimentKey(),
                decision.variantKey(),
                decision.guardrailStatus(),
                decision.decisionAction(),
                json(decision.reason()),
                "PAUSE_EXPERIMENT".equals(decision.decisionAction())
            );
        }
        return new ExperimentGuardrailRun(runId, "COMPLETED", summary, decisions);
    }

    public List<ExperimentGuardrailDecision> decisions(String experimentKey) {
        return jdbcTemplate.query(
            """
                select id, experiment_key, variant_key, guardrail_status, decision_action, reason_json
                from experiment_guardrail_decisions
                where experiment_key = ?
                order by created_at desc
                """,
            this::decision,
            experimentKey
        );
    }

    public boolean fallbackToControl(String experimentKey) {
        return jdbcTemplate.queryForObject(
            """
                select count(*) > 0
                from experiment_guardrail_decisions
                where experiment_key = ?
                  and decision_action in ('PAUSE_EXPERIMENT', 'FALLBACK_TO_CONTROL')
                  and created_at >= now() - interval '24 hours'
                """,
            Boolean.class,
            experimentKey
        );
    }

    private ExperimentGuardrailDecision decision(ResultSet rs, int rowNum) throws SQLException {
        return new ExperimentGuardrailDecision(
            rs.getObject("id", UUID.class),
            rs.getString("experiment_key"),
            rs.getString("variant_key"),
            rs.getString("guardrail_status"),
            rs.getString("decision_action"),
            readMap(rs.getString("reason_json"))
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize experiment guardrail JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to read experiment guardrail JSON", exception);
        }
    }
}
