package com.matchgraph.api.bandit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BanditDecisionRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BanditDecisionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID insertDecision(
        UUID policyId,
        UUID armId,
        UUID profileId,
        UUID candidateProfileId,
        String contextSegment,
        Map<String, Object> context,
        String selectedArmKey,
        String selectionReason,
        boolean safe
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into bandit_decisions (
                    id, policy_id, arm_id, profile_id, candidate_profile_id, context_segment,
                    decision_context_json, selected_arm_key, selection_reason, safe
                )
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
            id,
            policyId,
            armId,
            profileId,
            candidateProfileId,
            contextSegment,
            toJson(context == null ? Map.of() : context),
            selectedArmKey,
            selectionReason,
            safe
        );
        return id;
    }

    public Optional<BanditDecision> findDecision(UUID id) {
        return jdbcTemplate.query(
            """
                select id, policy_id, arm_id, profile_id, candidate_profile_id, context_segment,
                    decision_context_json::text as decision_context_json, selected_arm_key,
                    selection_reason, safe, created_at
                from bandit_decisions
                where id = ?
                """,
            this::mapDecision,
            id
        ).stream().findFirst();
    }

    private BanditDecision mapDecision(ResultSet rs, int rowNum) throws SQLException {
        return new BanditDecision(
            rs.getObject("id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getObject("arm_id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getString("context_segment"),
            map(rs.getString("decision_context_json")),
            rs.getString("selected_arm_key"),
            rs.getString("selection_reason"),
            rs.getBoolean("safe"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored bandit decision json is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("bandit decision value must be JSON serializable", exception);
        }
    }
}
