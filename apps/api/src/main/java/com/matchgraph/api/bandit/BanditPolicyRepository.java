package com.matchgraph.api.bandit;

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
public class BanditPolicyRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BanditPolicyRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createPolicy(BanditPolicyRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into bandit_policies (
                    id, policy_key, name, status, algorithm, epsilon, reward_config_json, config_json
                )
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """,
            id,
            request.policyKey().trim(),
            request.name().trim(),
            request.status() == null || request.status().isBlank() ? "ACTIVE" : request.status().trim(),
            request.algorithm() == null || request.algorithm().isBlank() ? "EPSILON_GREEDY" : request.algorithm().trim(),
            request.epsilon(),
            toJson(request.rewardConfig() == null ? Map.of() : request.rewardConfig()),
            toJson(request.config() == null ? Map.of() : request.config())
        );
        return id;
    }

    public UUID createArm(UUID policyId, BanditArmRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into bandit_arms (id, policy_id, arm_key, source_type, strategy, weight, config_json)
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
            id,
            policyId,
            request.armKey().trim(),
            request.sourceType().trim(),
            request.strategy().trim(),
            request.weight() == null ? BigDecimal.ONE : request.weight(),
            toJson(request.config() == null ? Map.of() : request.config())
        );
        return id;
    }

    public Optional<BanditPolicy> findPolicy(String policyKey) {
        List<BanditPolicy> policies = jdbcTemplate.query(
            """
                select id, policy_key, name, status, algorithm, epsilon,
                    reward_config_json::text as reward_config_json,
                    config_json::text as config_json, created_at, updated_at
                from bandit_policies
                where policy_key = ?
                """,
            this::mapPolicyWithoutArms,
            policyKey
        );
        return policies.stream().findFirst()
            .map(policy -> new BanditPolicy(
                policy.id(),
                policy.policyKey(),
                policy.name(),
                policy.status(),
                policy.algorithm(),
                policy.epsilon(),
                policy.rewardConfig(),
                policy.config(),
                policy.createdAt(),
                policy.updatedAt(),
                findArms(policy.id())
            ));
    }

    public Optional<BanditPolicy> findPolicy(UUID policyId) {
        List<BanditPolicy> policies = jdbcTemplate.query(
            """
                select id, policy_key, name, status, algorithm, epsilon,
                    reward_config_json::text as reward_config_json,
                    config_json::text as config_json, created_at, updated_at
                from bandit_policies
                where id = ?
                """,
            this::mapPolicyWithoutArms,
            policyId
        );
        return policies.stream().findFirst()
            .map(policy -> new BanditPolicy(
                policy.id(),
                policy.policyKey(),
                policy.name(),
                policy.status(),
                policy.algorithm(),
                policy.epsilon(),
                policy.rewardConfig(),
                policy.config(),
                policy.createdAt(),
                policy.updatedAt(),
                findArms(policy.id())
            ));
    }

    public List<BanditArm> findArms(UUID policyId) {
        return jdbcTemplate.query(
            """
                select id, policy_id, arm_key, source_type, strategy, weight, config_json::text as config_json, created_at
                from bandit_arms
                where policy_id = ?
                order by arm_key
                """,
            this::mapArm,
            policyId
        );
    }

    public List<BanditArmStats> stats(UUID policyId) {
        return jdbcTemplate.query(
            """
                select id, policy_id, arm_id, context_segment, decision_count, reward_count,
                    total_reward, average_reward, updated_at
                from bandit_arm_stats
                where policy_id = ?
                order by context_segment, arm_id
                """,
            this::mapStats,
            policyId
        );
    }

    public Optional<BanditArmStats> stats(UUID policyId, UUID armId, String contextSegment) {
        return jdbcTemplate.query(
            """
                select id, policy_id, arm_id, context_segment, decision_count, reward_count,
                    total_reward, average_reward, updated_at
                from bandit_arm_stats
                where policy_id = ?
                  and arm_id = ?
                  and context_segment = ?
                """,
            this::mapStats,
            policyId,
            armId,
            contextSegment
        ).stream().findFirst();
    }

    public void incrementDecision(UUID policyId, UUID armId, String contextSegment) {
        jdbcTemplate.update(
            """
                insert into bandit_arm_stats (
                    id, policy_id, arm_id, context_segment, decision_count, reward_count, total_reward, average_reward
                )
                values (?, ?, ?, ?, 1, 0, 0, 0)
                on conflict (policy_id, arm_id, context_segment)
                do update set decision_count = bandit_arm_stats.decision_count + 1,
                    updated_at = now()
                """,
            UUID.randomUUID(),
            policyId,
            armId,
            contextSegment
        );
    }

    public void incrementReward(UUID policyId, UUID armId, String contextSegment, BigDecimal rewardValue) {
        jdbcTemplate.update(
            """
                insert into bandit_arm_stats (
                    id, policy_id, arm_id, context_segment, decision_count, reward_count, total_reward, average_reward
                )
                values (?, ?, ?, ?, 0, 1, ?, ?)
                on conflict (policy_id, arm_id, context_segment)
                do update set reward_count = bandit_arm_stats.reward_count + 1,
                    total_reward = bandit_arm_stats.total_reward + excluded.total_reward,
                    average_reward = (bandit_arm_stats.total_reward + excluded.total_reward) / greatest(1, bandit_arm_stats.reward_count + 1),
                    updated_at = now()
                """,
            UUID.randomUUID(),
            policyId,
            armId,
            contextSegment,
            rewardValue,
            rewardValue
        );
    }

    private BanditPolicy mapPolicyWithoutArms(ResultSet rs, int rowNum) throws SQLException {
        return new BanditPolicy(
            rs.getObject("id", UUID.class),
            rs.getString("policy_key"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getString("algorithm"),
            rs.getBigDecimal("epsilon"),
            map(rs.getString("reward_config_json")),
            map(rs.getString("config_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            List.of()
        );
    }

    private BanditArm mapArm(ResultSet rs, int rowNum) throws SQLException {
        return new BanditArm(
            rs.getObject("id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getString("arm_key"),
            rs.getString("source_type"),
            rs.getString("strategy"),
            rs.getBigDecimal("weight"),
            map(rs.getString("config_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private BanditArmStats mapStats(ResultSet rs, int rowNum) throws SQLException {
        return new BanditArmStats(
            rs.getObject("id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getObject("arm_id", UUID.class),
            rs.getString("context_segment"),
            rs.getInt("decision_count"),
            rs.getInt("reward_count"),
            rs.getBigDecimal("total_reward"),
            rs.getBigDecimal("average_reward"),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored bandit json is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("bandit value must be JSON serializable", exception);
        }
    }
}
