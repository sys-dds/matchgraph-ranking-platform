package com.matchgraph.api.metrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
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
        return new RankingMetricsIngestResponse(servedRows.size(), interactionRows.size(), skippedInteractionRows(), allRows.size());
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
            (rs, rowNum) -> metricRow(rs, "SERVED", null, null)
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
                    e.event_type,
                    e.id as interaction_event_id
                from interaction_events e
                join ranking_decision_logs l
                  on l.profile_id = e.actor_profile_id
                 and l.retrieval_run_id = e.retrieval_run_id
                 and l.ranking_version = e.ranking_version
                join ranking_decision_items di
                  on di.decision_log_id = l.id
                 and di.candidate_profile_id = e.target_profile_id
                 and di.position = e.feed_position
                left join feed_snapshots f on f.ranking_decision_log_id = l.id
                where e.event_type is not null
                  and e.retrieval_run_id is not null
                  and e.feed_position is not null
                  and e.ranking_version is not null
                  and (
                    e.candidate_source is null
                    or exists (
                        select 1
                        from jsonb_array_elements_text(di.source_types_json) source_type(value)
                        where source_type.value = e.candidate_source
                    )
                  )
                """,
            (rs, rowNum) -> metricRow(rs, "INTERACTION", rs.getString("event_type"), rs.getObject("interaction_event_id", UUID.class))
        );
    }

    private int skippedInteractionRows() {
        Integer skipped = jdbcTemplate.queryForObject(
            """
                select count(*)
                from interaction_events e
                where e.event_type is not null
                  and not exists (
                    select 1
                    from ranking_decision_logs l
                    join ranking_decision_items di
                      on di.decision_log_id = l.id
                     and di.candidate_profile_id = e.target_profile_id
                     and di.position = e.feed_position
                    where l.profile_id = e.actor_profile_id
                      and l.retrieval_run_id = e.retrieval_run_id
                      and l.ranking_version = e.ranking_version
                      and e.retrieval_run_id is not null
                      and e.feed_position is not null
                      and e.ranking_version is not null
                      and (
                        e.candidate_source is null
                        or exists (
                            select 1
                            from jsonb_array_elements_text(di.source_types_json) source_type(value)
                            where source_type.value = e.candidate_source
                        )
                      )
                  )
                """,
            Integer.class
        );
        return skipped == null ? 0 : skipped;
    }

    private RankingMetricRow metricRow(ResultSet rs, String rowType, String eventType, UUID interactionEventId) throws SQLException {
        int position = rs.getInt("position");
        UUID decisionLogId = rs.getObject("decision_log_id", UUID.class);
        UUID candidateProfileId = rs.getObject("candidate_profile_id", UUID.class);
        return new RankingMetricRow(
            metricEventId(rowType, decisionLogId, candidateProfileId, position, eventType, interactionEventId),
            rs.getString("ranking_version"),
            blankToNull(rs.getString("experiment_key")),
            blankToNull(rs.getString("variant")),
            (Boolean) rs.getObject("holdout"),
            rs.getString("candidate_source"),
            position,
            positionBucket(position),
            eventType,
            rs.getObject("profile_id", UUID.class),
            candidateProfileId,
            decisionLogId,
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

    private String metricEventId(String rowType, UUID decisionLogId, UUID candidateProfileId, int position, String eventType, UUID interactionEventId) {
        String identity = String.join(
            "|",
            rowType,
            decisionLogId.toString(),
            candidateProfileId.toString(),
            String.valueOf(position),
            eventType == null ? "" : eventType,
            interactionEventId == null ? "" : interactionEventId.toString()
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
