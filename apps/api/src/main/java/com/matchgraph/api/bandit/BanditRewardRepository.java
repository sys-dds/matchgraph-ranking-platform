package com.matchgraph.api.bandit;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BanditRewardRepository {

    private final JdbcTemplate jdbcTemplate;

    public BanditRewardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID insertReward(
        UUID policyId,
        UUID armId,
        UUID decisionId,
        UUID profileId,
        UUID candidateProfileId,
        String rewardEventType,
        BigDecimal rewardValue,
        UUID interactionEventId
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into bandit_rewards (
                    id, policy_id, arm_id, decision_id, profile_id, candidate_profile_id,
                    reward_event_type, reward_value, interaction_event_id
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            id,
            policyId,
            armId,
            decisionId,
            profileId,
            candidateProfileId,
            rewardEventType,
            rewardValue,
            interactionEventId
        );
        return id;
    }

    public Optional<BanditReward> findReward(UUID id) {
        return jdbcTemplate.query(
            """
                select id, policy_id, arm_id, decision_id, profile_id, candidate_profile_id,
                    reward_event_type, reward_value, interaction_event_id, created_at
                from bandit_rewards
                where id = ?
                """,
            this::mapReward,
            id
        ).stream().findFirst();
    }

    private BanditReward mapReward(ResultSet rs, int rowNum) throws SQLException {
        return new BanditReward(
            rs.getObject("id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getObject("arm_id", UUID.class),
            rs.getObject("decision_id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getString("reward_event_type"),
            rs.getBigDecimal("reward_value"),
            rs.getObject("interaction_event_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }
}
