package com.matchgraph.api.matching;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingRepository {

    private final JdbcTemplate jdbcTemplate;

    public MatchingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SwipeResponse> findSwipeByClientEvent(UUID actorProfileId, String clientEventId) {
        List<SwipeResponse> swipes = jdbcTemplate.query(
            """
                select id, actor_profile_id, target_profile_id, direction, client_event_id, created_at
                from swipes
                where actor_profile_id = ?
                  and client_event_id = ?
                """,
            (rs, rowNum) -> mapSwipe(rs, true, false, null),
            actorProfileId,
            clientEventId
        );
        return swipes.stream().findFirst();
    }

    public void lockPair(UUID firstProfileId, UUID secondProfileId) {
        UUID profileA = firstProfileId.compareTo(secondProfileId) < 0 ? firstProfileId : secondProfileId;
        UUID profileB = firstProfileId.compareTo(secondProfileId) < 0 ? secondProfileId : firstProfileId;
        jdbcTemplate.query(
            "select pg_advisory_xact_lock(hashtext(?))",
            rs -> null,
            profileA + ":" + profileB
        );
    }

    public SwipeResponse createSwipe(UUID actorProfileId, SwipeRequest request) {
        return jdbcTemplate.queryForObject(
            """
                insert into swipes (id, actor_profile_id, target_profile_id, direction, client_event_id)
                values (?, ?, ?, ?, ?)
                returning id, actor_profile_id, target_profile_id, direction, client_event_id, created_at
                """,
            (rs, rowNum) -> mapSwipe(rs, false, false, null),
            UUID.randomUUID(),
            actorProfileId,
            request.targetProfileId(),
            request.direction(),
            request.clientEventId().trim()
        );
    }

    public boolean reciprocalRightSwipeExists(UUID actorProfileId, UUID targetProfileId) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                select exists (
                    select 1
                    from swipes
                    where actor_profile_id = ?
                      and target_profile_id = ?
                      and direction = 'RIGHT'
                )
                """,
            Boolean.class,
            targetProfileId,
            actorProfileId
        );
        return Boolean.TRUE.equals(exists);
    }

    public boolean rightSwipePairComplete(UUID firstProfileId, UUID secondProfileId) {
        Integer count = jdbcTemplate.queryForObject(
            """
                select count(*)::int
                from swipes
                where direction = 'RIGHT'
                  and (
                    (actor_profile_id = ? and target_profile_id = ?)
                    or (actor_profile_id = ? and target_profile_id = ?)
                  )
                """,
            Integer.class,
            firstProfileId,
            secondProfileId,
            secondProfileId,
            firstProfileId
        );
        return count != null && count >= 2;
    }

    public MatchCreation createMatchIfAbsent(UUID firstProfileId, UUID secondProfileId) {
        UUID profileA = firstProfileId.compareTo(secondProfileId) < 0 ? firstProfileId : secondProfileId;
        UUID profileB = firstProfileId.compareTo(secondProfileId) < 0 ? secondProfileId : firstProfileId;
        List<MatchResponse> created = jdbcTemplate.query(
            """
                insert into matches (id, profile_a_id, profile_b_id, status)
                values (?, ?, ?, 'ACTIVE')
                on conflict (profile_a_id, profile_b_id) do nothing
                returning id, profile_a_id, profile_b_id, created_at, status
                """,
            this::mapMatch,
            UUID.randomUUID(),
            profileA,
            profileB
        );
        if (!created.isEmpty()) {
            return new MatchCreation(created.getFirst(), true);
        }
        return findMatch(firstProfileId, secondProfileId)
            .map(match -> new MatchCreation(match, false))
            .orElseThrow();
    }

    public Optional<MatchResponse> findMatch(UUID firstProfileId, UUID secondProfileId) {
        UUID profileA = firstProfileId.compareTo(secondProfileId) < 0 ? firstProfileId : secondProfileId;
        UUID profileB = firstProfileId.compareTo(secondProfileId) < 0 ? secondProfileId : firstProfileId;
        List<MatchResponse> matches = jdbcTemplate.query(
            """
                select id, profile_a_id, profile_b_id, created_at, status
                from matches
                where profile_a_id = ?
                  and profile_b_id = ?
                  and status = 'ACTIVE'
                """,
            this::mapMatch,
            profileA,
            profileB
        );
        return matches.stream().findFirst();
    }

    public List<MatchResponse> matches(UUID profileId) {
        return jdbcTemplate.query(
            """
                select id, profile_a_id, profile_b_id, created_at, status
                from matches
                where (profile_a_id = ? or profile_b_id = ?)
                  and status = 'ACTIVE'
                order by created_at desc, id
                """,
            this::mapMatch,
            profileId,
            profileId
        );
    }

    public boolean blockedOrSuppressed(UUID actorProfileId, UUID targetProfileId) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                select exists (
                    select 1
                    from profile_graph_edges
                    where status = 'ACTIVE'
                      and (
                        (edge_type = 'BLOCK' and (
                            (source_profile_id = ? and target_profile_id = ?)
                            or (source_profile_id = ? and target_profile_id = ?)
                        ))
                        or (edge_type = 'MUTE' and source_profile_id = ? and target_profile_id = ?)
                      )
                )
                """,
            Boolean.class,
            actorProfileId,
            targetProfileId,
            targetProfileId,
            actorProfileId,
            actorProfileId,
            targetProfileId
        );
        return Boolean.TRUE.equals(exists);
    }

    public boolean safetyBlocked(UUID firstProfileId, UUID secondProfileId) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                select exists (
                    select 1
                    from profile_safety_states
                    where profile_id in (?, ?)
                      and safety_state = 'BLOCKED'
                )
                """,
            Boolean.class,
            firstProfileId,
            secondProfileId
        );
        return Boolean.TRUE.equals(exists);
    }

    private SwipeResponse mapSwipe(ResultSet rs, boolean duplicate, boolean matchCreated, MatchResponse match) throws SQLException {
        return new SwipeResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("actor_profile_id", UUID.class),
            rs.getObject("target_profile_id", UUID.class),
            rs.getString("direction"),
            rs.getString("client_event_id"),
            rs.getObject("created_at", OffsetDateTime.class),
            duplicate,
            matchCreated,
            match
        );
    }

    private MatchResponse mapMatch(ResultSet rs, int rowNum) throws SQLException {
        return new MatchResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("profile_a_id", UUID.class),
            rs.getObject("profile_b_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("status")
        );
    }

    public record MatchCreation(MatchResponse match, boolean created) {
    }
}
