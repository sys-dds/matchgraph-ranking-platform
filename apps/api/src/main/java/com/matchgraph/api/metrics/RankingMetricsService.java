package com.matchgraph.api.metrics;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RankingMetricsService {

    private final JdbcTemplate jdbcTemplate;
    private final RankingMetricsRepository rankingMetricsRepository;

    public RankingMetricsService(JdbcTemplate jdbcTemplate, RankingMetricsRepository rankingMetricsRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.rankingMetricsRepository = rankingMetricsRepository;
    }

    public RankingMetricsIngestResponse ingest() {
        List<RankingMetricRow> servedRows = servedRows();
        List<RankingMetricRow> interactionRows = interactionRows();
        List<RankingMetricRow> allRows = new ArrayList<>(servedRows);
        allRows.addAll(interactionRows);
        rankingMetricsRepository.insert(allRows);
        return new RankingMetricsIngestResponse(servedRows.size(), interactionRows.size(), allRows.size());
    }

    public RankingMetricsSummaryResponse summary() {
        return rankingMetricsRepository.summary();
    }

    private List<RankingMetricRow> servedRows() {
        return jdbcTemplate.query(
            """
                select l.ranking_version,
                    l.ranking_context_json ->> 'experimentKey' as experiment_key,
                    l.ranking_context_json ->> 'assignedVariant' as variant,
                    case
                        when l.ranking_context_json ->> 'assignmentId' is not null
                            then (l.ranking_context_json ->> 'assignedVariant') is null
                        else null
                    end as holdout,
                    coalesce(i.source_types_json ->> 0, 'UNKNOWN') as candidate_source,
                    i.position,
                    l.profile_id,
                    i.candidate_profile_id,
                    l.id as decision_log_id,
                    f.id as feed_snapshot_id,
                    coalesce(f.created_at, l.created_at) as occurred_at
                from ranking_decision_items i
                join ranking_decision_logs l on l.id = i.decision_log_id
                left join feed_snapshots f on f.ranking_decision_log_id = l.id
                """,
            (rs, rowNum) -> metricRow(rs, null)
        );
    }

    private List<RankingMetricRow> interactionRows() {
        return jdbcTemplate.query(
            """
                select l.ranking_version,
                    l.ranking_context_json ->> 'experimentKey' as experiment_key,
                    l.ranking_context_json ->> 'assignedVariant' as variant,
                    case
                        when l.ranking_context_json ->> 'assignmentId' is not null
                            then (l.ranking_context_json ->> 'assignedVariant') is null
                        else null
                    end as holdout,
                    coalesce(di.source_types_json ->> 0, e.candidate_source, 'UNKNOWN') as candidate_source,
                    coalesce(e.feed_position, di.position) as position,
                    l.profile_id,
                    e.target_profile_id as candidate_profile_id,
                    l.id as decision_log_id,
                    f.id as feed_snapshot_id,
                    e.occurred_at,
                    e.event_type
                from interaction_events e
                join ranking_decision_logs l
                  on l.profile_id = e.actor_profile_id
                 and l.ranking_version = coalesce(e.ranking_version, l.ranking_version)
                join ranking_decision_items di
                  on di.decision_log_id = l.id
                 and di.candidate_profile_id = e.target_profile_id
                left join feed_snapshots f on f.ranking_decision_log_id = l.id
                where e.event_type is not null
                """,
            (rs, rowNum) -> metricRow(rs, rs.getString("event_type"))
        );
    }

    private RankingMetricRow metricRow(ResultSet rs, String eventType) throws SQLException {
        int position = rs.getInt("position");
        return new RankingMetricRow(
            rs.getString("ranking_version"),
            blankToNull(rs.getString("experiment_key")),
            blankToNull(rs.getString("variant")),
            (Boolean) rs.getObject("holdout"),
            rs.getString("candidate_source"),
            position,
            positionBucket(position),
            eventType,
            rs.getObject("profile_id", UUID.class),
            rs.getObject("candidate_profile_id", UUID.class),
            rs.getObject("decision_log_id", UUID.class),
            rs.getObject("feed_snapshot_id", UUID.class),
            rs.getObject("occurred_at", OffsetDateTime.class)
        );
    }

    private String positionBucket(int position) {
        if (position <= 1) {
            return "1";
        }
        if (position <= 5) {
            return "2-5";
        }
        if (position <= 10) {
            return "6-10";
        }
        if (position <= 20) {
            return "11-20";
        }
        return "21+";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
