package com.matchgraph.api.feed;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.ranking.RankingDecision;
import com.matchgraph.api.ranking.RankingDecisionItem;
import com.matchgraph.api.ranking.RankingReason;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeedRepository {

    private static final TypeReference<List<RankingReason>> REASONS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FeedRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createSnapshot(UUID profileId, RankingDecision decision) {
        jdbcTemplate.update(
            """
                update feed_snapshots
                set status = 'SUPERSEDED'
                where profile_id = ?
                  and status = 'ACTIVE'
                """,
            profileId
        );
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into feed_snapshots (
                    id, profile_id, retrieval_run_id, feature_snapshot_run_id,
                    ranking_decision_log_id, ranking_version, status
                )
                values (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """,
            id,
            profileId,
            decision.retrievalRunId(),
            decision.featureSnapshotRunId(),
            decision.id(),
            decision.rankingVersion()
        );
        return id;
    }

    public void insertItem(UUID feedSnapshotId, RankingDecision decision, RankingDecisionItem rankedItem) {
        jdbcTemplate.update(
            """
                insert into feed_items (
                    id, feed_snapshot_id, retrieval_run_id, ranking_decision_log_id,
                    candidate_profile_id, position, score, ranking_reasons_json,
                    source_types_json, feature_snapshot_id
                )
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """,
            UUID.randomUUID(),
            feedSnapshotId,
            decision.retrievalRunId(),
            decision.id(),
            rankedItem.candidateProfileId(),
            rankedItem.position(),
            rankedItem.finalScore(),
            toJson(rankedItem.reasons()),
            toJson(rankedItem.sourceTypes()),
            rankedItem.featureSnapshotId()
        );
    }

    public Optional<FeedSnapshot> activeSnapshot(UUID profileId) {
        List<FeedSnapshot> snapshots = jdbcTemplate.query(
            """
                select id, profile_id, retrieval_run_id, feature_snapshot_run_id,
                    ranking_decision_log_id, ranking_version, status, created_at
                from feed_snapshots
                where profile_id = ?
                  and status = 'ACTIVE'
                order by created_at desc
                limit 1
                """,
            this::mapSnapshotWithoutItems,
            profileId
        );
        return snapshots.stream().findFirst();
    }

    public Optional<FeedSnapshot> findSnapshot(UUID profileId, UUID snapshotId) {
        List<FeedSnapshot> snapshots = jdbcTemplate.query(
            """
                select id, profile_id, retrieval_run_id, feature_snapshot_run_id,
                    ranking_decision_log_id, ranking_version, status, created_at
                from feed_snapshots
                where profile_id = ?
                  and id = ?
                """,
            this::mapSnapshotWithoutItems,
            profileId,
            snapshotId
        );
        return snapshots.stream().findFirst();
    }

    public List<FeedItem> page(UUID feedSnapshotId, int afterPosition, int limit) {
        return jdbcTemplate.query(
            """
                select id, feed_snapshot_id, retrieval_run_id, ranking_decision_log_id,
                    candidate_profile_id, position, score, ranking_reasons_json::text as ranking_reasons_json,
                    source_types_json::text as source_types_json, feature_snapshot_id, created_at
                from feed_items
                where feed_snapshot_id = ?
                  and position > ?
                order by position, id
                limit ?
                """,
            this::mapItem,
            feedSnapshotId,
            afterPosition,
            limit
        );
    }

    private FeedSnapshot mapSnapshotWithoutItems(ResultSet rs, int rowNum) throws SQLException {
        return new FeedSnapshot(
            rs.getObject("id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getObject("retrieval_run_id", UUID.class),
            rs.getObject("feature_snapshot_run_id", UUID.class),
            rs.getObject("ranking_decision_log_id", UUID.class),
            rs.getString("ranking_version"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class),
            List.of()
        );
    }

    private FeedItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new FeedItem(
            rs.getObject("id", UUID.class),
            rs.getObject("feed_snapshot_id", UUID.class),
            rs.getObject("retrieval_run_id", UUID.class),
            rs.getObject("ranking_decision_log_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getInt("position"),
            rs.getBigDecimal("score"),
            reasons(rs.getString("ranking_reasons_json")),
            stringList(rs.getString("source_types_json")),
            rs.getObject("feature_snapshot_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("feed value must be JSON serializable", exception);
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
}
