package com.matchgraph.api.metrics;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class RankingMetricsRepository {

    private final String clickHouseUrl;

    public RankingMetricsRepository(@Value("${matchgraph.clickhouse.url}") String clickHouseUrl) {
        this.clickHouseUrl = clickHouseUrl;
    }

    public void ensureTable() {
        try (Connection connection = DriverManager.getConnection(clickHouseUrl);
             Statement statement = connection.createStatement()) {
            statement.execute(
                """
                    create table if not exists ranking_events (
                        metric_event_id String,
                        ranking_version String,
                        experiment_key Nullable(String),
                        variant Nullable(String),
                        holdout Nullable(Bool),
                        candidate_source String,
                        position UInt32,
                        position_bucket String,
                        event_type Nullable(String),
                        profile_id UUID,
                        candidate_profile_id UUID,
                        decision_log_id UUID,
                        feed_snapshot_id Nullable(UUID),
                        occurred_at DateTime64(3, 'UTC')
                    )
                    engine = MergeTree
                    order by (metric_event_id, occurred_at, ranking_version, decision_log_id, position)
                    """
            );
            statement.execute("alter table ranking_events add column if not exists metric_event_id String first");
        } catch (SQLException exception) {
            throw new IllegalStateException("unable to ensure ClickHouse ranking_events table", exception);
        }
    }

    public void insert(List<RankingMetricRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        ensureTable();
        Set<String> existingIds = existingMetricEventIds(rows);
        try (Connection connection = DriverManager.getConnection(clickHouseUrl);
             PreparedStatement statement = connection.prepareStatement(
                 """
                     insert into ranking_events (
                        metric_event_id, ranking_version, experiment_key, variant, holdout, candidate_source,
                        position, position_bucket, event_type, profile_id, candidate_profile_id,
                        decision_log_id, feed_snapshot_id, occurred_at
                     )
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """
             )) {
            for (RankingMetricRow row : rows) {
                if (existingIds.contains(row.metricEventId())) {
                    continue;
                }
                statement.setString(1, row.metricEventId());
                statement.setString(2, row.rankingVersion());
                statement.setString(3, row.experimentKey());
                statement.setString(4, row.variant());
                if (row.holdout() == null) {
                    statement.setObject(5, null);
                } else {
                    statement.setBoolean(5, row.holdout());
                }
                statement.setString(6, row.candidateSource());
                statement.setInt(7, row.position());
                statement.setString(8, row.positionBucket());
                statement.setString(9, row.eventType());
                statement.setObject(10, row.profileId());
                statement.setObject(11, row.candidateProfileId());
                statement.setObject(12, row.decisionLogId());
                statement.setObject(13, row.feedSnapshotId());
                statement.setObject(14, row.occurredAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException exception) {
            throw new IllegalStateException("unable to insert ranking metric rows", exception);
        }
    }

    private Set<String> existingMetricEventIds(List<RankingMetricRow> rows) {
        Set<String> ids = new HashSet<>();
        String inList = rows.stream()
            .map(row -> "'" + row.metricEventId().replace("'", "''") + "'")
            .collect(java.util.stream.Collectors.joining(","));
        if (inList.isBlank()) {
            return ids;
        }
        try (Connection connection = DriverManager.getConnection(clickHouseUrl);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select metric_event_id from ranking_events where metric_event_id in (" + inList + ")")) {
            while (rs.next()) {
                ids.add(rs.getString("metric_event_id"));
            }
            return ids;
        } catch (SQLException exception) {
            throw new IllegalStateException("unable to query existing ranking metric row identities", exception);
        }
    }

    public RankingMetricsSummaryResponse summary() {
        ensureTable();
        List<RankingMetricsSummaryRow> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(clickHouseUrl);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 """
                     select
                        ranking_version,
                        ifNull(experiment_key, '') as experiment_key,
                        ifNull(variant, '') as variant,
                        candidate_source,
                        position_bucket,
                        ifNull(event_type, '') as event_type,
                        uniqExactIf(metric_event_id, isNull(event_type)) as served_count,
                        uniqExactIf(metric_event_id, isNotNull(event_type)) as interaction_count,
                        uniqExactIf(metric_event_id, event_type in ('LIKE', 'PROFILE_VIEW', 'MATCH_CREATED')) as positive_count,
                        uniqExactIf(metric_event_id, event_type in ('PASS', 'SKIP', 'BLOCK', 'REPORT')) as negative_count,
                        uniqExactIf(metric_event_id, event_type = 'MATCH_CREATED') as match_count,
                        uniqExactIf(metric_event_id, event_type = 'LIKE') as like_count,
                        uniqExactIf(metric_event_id, event_type = 'PASS') as pass_count,
                        uniqExactIf(metric_event_id, event_type = 'REPORT') as report_count,
                        uniqExactIf(metric_event_id, event_type = 'BLOCK') as block_count,
                        if(served_count = 0, 0, like_count / served_count) as ctr_like_rate,
                        if(served_count = 0, 0, match_count / served_count) as match_rate
                     from ranking_events
                     group by ranking_version, experiment_key, variant, candidate_source, position_bucket, event_type
                     order by ranking_version, experiment_key, variant, candidate_source, position_bucket, event_type
                     """
             )) {
            while (rs.next()) {
                rows.add(new RankingMetricsSummaryRow(
                    rs.getString("ranking_version"),
                    blankToNull(rs.getString("experiment_key")),
                    blankToNull(rs.getString("variant")),
                    rs.getString("candidate_source"),
                    rs.getString("position_bucket"),
                    blankToNull(rs.getString("event_type")),
                    rs.getLong("served_count"),
                    rs.getLong("interaction_count"),
                    rs.getLong("positive_count"),
                    rs.getLong("negative_count"),
                    rs.getLong("match_count"),
                    rs.getLong("like_count"),
                    rs.getLong("pass_count"),
                    rs.getLong("report_count"),
                    rs.getLong("block_count"),
                    rs.getDouble("ctr_like_rate"),
                    rs.getDouble("match_rate")
                ));
            }
            return new RankingMetricsSummaryResponse(rows);
        } catch (SQLException exception) {
            throw new IllegalStateException("unable to query ranking metric summary", exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
