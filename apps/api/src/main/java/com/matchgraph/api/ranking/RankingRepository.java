package com.matchgraph.api.ranking;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
public class RankingRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<RankingReason>> REASONS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<UUID>> UUID_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RankingRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public RankingVersion version(String requestedVersion) {
        String versionKey = requestedVersion == null || requestedVersion.isBlank() ? "v1_balanced" : requestedVersion.trim();
        return jdbcTemplate.queryForObject(
            """
                select version_key, description, active, policy_json::text as policy_json, created_at
                from ranking_versions
                where version_key = ?
                """,
            this::mapVersion,
            versionKey
        );
    }

    public UUID createDecision(
        UUID profileId,
        UUID retrievalRunId,
        UUID featureSnapshotRunId,
        String rankingVersion,
        String decisionType,
        int candidateCount,
        int servedCount,
        List<UUID> candidatePool,
        Map<String, Object> rankingContext
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into ranking_decision_logs (
                    id, profile_id, retrieval_run_id, feature_snapshot_run_id, ranking_version,
                    decision_type, candidate_count, served_count, candidate_pool_json, ranking_context_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """,
            id,
            profileId,
            retrievalRunId,
            featureSnapshotRunId,
            rankingVersion,
            decisionType,
            candidateCount,
            servedCount,
            toJson(candidatePool),
            toJson(rankingContext == null ? Map.of() : rankingContext)
        );
        return id;
    }

    public void insertItem(UUID decisionLogId, RankingDecisionItem item) {
        jdbcTemplate.update(
            """
                insert into ranking_decision_items (
                    decision_log_id, candidate_profile_id, feature_snapshot_id, position, base_score,
                    final_score, reasons_json, diversity_adjustments_json, source_types_json
                )
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
                """,
            decisionLogId,
            item.candidateProfileId(),
            item.featureSnapshotId(),
            item.position(),
            item.baseScore(),
            item.finalScore(),
            toJson(item.reasons()),
            toJson(item.diversityAdjustments()),
            toJson(item.sourceTypes())
        );
    }

    public Optional<RankingDecision> findDecision(UUID profileId, UUID decisionLogId) {
        List<RankingDecision> decisions = jdbcTemplate.query(
            """
                select id, profile_id, retrieval_run_id, feature_snapshot_run_id, ranking_version,
                    decision_type, candidate_count, served_count, candidate_pool_json::text as candidate_pool_json,
                    ranking_context_json::text as ranking_context_json, created_at
                from ranking_decision_logs
                where id = ?
                  and profile_id = ?
                """,
            this::mapDecisionWithoutItems,
            decisionLogId,
            profileId
        );
        return decisions.stream().findFirst()
            .map(decision -> new RankingDecision(
                decision.id(),
                decision.profileId(),
                decision.retrievalRunId(),
                decision.featureSnapshotRunId(),
                decision.rankingVersion(),
                decision.decisionType(),
                decision.candidateCount(),
                decision.servedCount(),
                decision.candidatePool(),
                decision.rankingContext(),
                decision.createdAt(),
                findDecisionItems(decision.id())
            ));
    }

    public Optional<RankingDecision> findDecision(UUID decisionLogId) {
        List<RankingDecision> decisions = jdbcTemplate.query(
            """
                select id, profile_id, retrieval_run_id, feature_snapshot_run_id, ranking_version,
                    decision_type, candidate_count, served_count, candidate_pool_json::text as candidate_pool_json,
                    ranking_context_json::text as ranking_context_json, created_at
                from ranking_decision_logs
                where id = ?
                """,
            this::mapDecisionWithoutItems,
            decisionLogId
        );
        return decisions.stream().findFirst()
            .map(decision -> new RankingDecision(
                decision.id(),
                decision.profileId(),
                decision.retrievalRunId(),
                decision.featureSnapshotRunId(),
                decision.rankingVersion(),
                decision.decisionType(),
                decision.candidateCount(),
                decision.servedCount(),
                decision.candidatePool(),
                decision.rankingContext(),
                decision.createdAt(),
                findDecisionItems(decision.id())
            ));
    }

    public List<RankingDecisionItem> findDecisionItems(UUID decisionLogId) {
        return jdbcTemplate.query(
            """
                select candidate_profile_id, feature_snapshot_id, position, base_score, final_score,
                    reasons_json::text as reasons_json, diversity_adjustments_json::text as diversity_adjustments_json,
                    source_types_json::text as source_types_json, created_at
                from ranking_decision_items
                where decision_log_id = ?
                order by position
                """,
            this::mapItem,
            decisionLogId
        );
    }

    public List<UUID> recentlySeenCandidateIds(UUID profileId) {
        return jdbcTemplate.queryForList(
            """
                select distinct target_profile_id
                from interaction_events
                where actor_profile_id = ?
                  and event_type in ('IMPRESSION', 'PROFILE_VIEW', 'SKIP', 'PASS', 'LIKE')
                  and occurred_at >= now() - interval '14 days'
                """,
            UUID.class,
            profileId
        );
    }

    private RankingVersion mapVersion(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> policyMap = map(rs.getString("policy_json"));
        Map<String, BigDecimal> signals = new LinkedHashMap<>();
        Object rawSignals = policyMap.get("signals");
        if (rawSignals instanceof Map<?, ?> rawSignalMap) {
            rawSignalMap.forEach((key, value) -> signals.put(String.valueOf(key), new BigDecimal(String.valueOf(value))));
        }
        Map<String, Object> diversity = new LinkedHashMap<>();
        Object rawDiversity = policyMap.get("diversity");
        if (rawDiversity instanceof Map<?, ?> rawDiversityMap) {
            rawDiversityMap.forEach((key, value) -> diversity.put(String.valueOf(key), value));
        }
        return new RankingVersion(
            rs.getString("version_key"),
            rs.getString("description"),
            rs.getBoolean("active"),
            new RankingPolicy(signals, diversity),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private RankingDecision mapDecisionWithoutItems(ResultSet rs, int rowNum) throws SQLException {
        return new RankingDecision(
            rs.getObject("id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("retrieval_run_id", UUID.class),
            rs.getObject("feature_snapshot_run_id", UUID.class),
            rs.getString("ranking_version"),
            rs.getString("decision_type"),
            rs.getInt("candidate_count"),
            rs.getInt("served_count"),
            uuidList(rs.getString("candidate_pool_json")),
            map(rs.getString("ranking_context_json")),
            rs.getObject("created_at", OffsetDateTime.class),
            List.of()
        );
    }

    private RankingDecisionItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new RankingDecisionItem(
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getObject("feature_snapshot_id", UUID.class),
            rs.getInt("position"),
            rs.getBigDecimal("base_score"),
            rs.getBigDecimal("final_score"),
            reasons(rs.getString("reasons_json")),
            reasons(rs.getString("diversity_adjustments_json")),
            stringList(rs.getString("source_types_json")),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("ranking decision value must be JSON serializable", exception);
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored ranking policy json is invalid", exception);
        }
    }

    private List<RankingReason> reasons(String json) {
        try {
            return objectMapper.readValue(json, REASONS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored ranking reasons json is invalid", exception);
        }
    }

    private List<String> stringList(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored source types json is invalid", exception);
        }
    }

    private List<UUID> uuidList(String json) {
        try {
            return objectMapper.readValue(json, UUID_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored candidate pool json is invalid", exception);
        }
    }
}
