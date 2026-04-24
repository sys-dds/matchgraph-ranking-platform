package com.matchgraph.api.profile;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ProfileResponse create(UUID id, CreateProfileRequest request, BigDecimal completenessScore) {
        return jdbcTemplate.queryForObject(
            """
                insert into profiles (
                    id, external_ref, display_name, profile_type, status,
                    bio, city, region, country, profile_completeness_score
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id, external_ref, display_name, profile_type, status, bio, city, region, country,
                    last_active_at, profile_completeness_score, embedding_status, created_at, updated_at
                """,
            this::mapProfileWithoutChildren,
            id,
            request.externalRef().trim(),
            request.displayName().trim(),
            request.profileType(),
            request.status(),
            trimToNull(request.bio()),
            trimToNull(request.city()),
            trimToNull(request.region()),
            trimToNull(request.country()),
            completenessScore
        );
    }

    public ProfileResponse update(UUID id, UpdateProfileRequest request, BigDecimal completenessScore) {
        return jdbcTemplate.queryForObject(
            """
                update profiles
                set display_name = coalesce(?, display_name),
                    status = coalesce(?, status),
                    bio = coalesce(?, bio),
                    city = coalesce(?, city),
                    region = coalesce(?, region),
                    country = coalesce(?, country),
                    last_active_at = coalesce(?, last_active_at),
                    profile_completeness_score = ?,
                    updated_at = now()
                where id = ?
                returning id, external_ref, display_name, profile_type, status, bio, city, region, country,
                    last_active_at, profile_completeness_score, embedding_status, created_at, updated_at
                """,
            this::mapProfileWithoutChildren,
            trimToNull(request.displayName()),
            request.status(),
            trimToNull(request.bio()),
            trimToNull(request.city()),
            trimToNull(request.region()),
            trimToNull(request.country()),
            request.lastActiveAt(),
            completenessScore,
            id
        );
    }

    public Optional<ProfileResponse> findById(UUID id) {
        List<ProfileResponse> profiles = jdbcTemplate.query(
            """
                select id, external_ref, display_name, profile_type, status, bio, city, region, country,
                    last_active_at, profile_completeness_score, embedding_status, created_at, updated_at
                from profiles
                where id = ?
                """,
            this::mapProfileWithoutChildren,
            id
        );
        return profiles.stream().findFirst();
    }

    public List<ProfileResponse> find(String profileType, String status, int limit) {
        return jdbcTemplate.query(
            """
                select id, external_ref, display_name, profile_type, status, bio, city, region, country,
                    last_active_at, profile_completeness_score, embedding_status, created_at, updated_at
                from profiles
                where (? is null or profile_type = ?)
                  and (? is null or status = ?)
                order by created_at desc, id
                limit ?
                """,
            this::mapProfileWithoutChildren,
            profileType,
            profileType,
            status,
            status,
            limit
        );
    }

    public boolean exists(UUID id) {
        Boolean exists = jdbcTemplate.queryForObject("select exists (select 1 from profiles where id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(exists);
    }

    public void createDefaultSafetyState(UUID profileId) {
        jdbcTemplate.update(
            """
                insert into profile_safety_states (profile_id, safety_state)
                values (?, 'UNREVIEWED')
                on conflict (profile_id) do nothing
                """,
            profileId
        );
    }

    public void createSafetyEvent(UUID eventId, UUID profileId, String safetyState, String reason) {
        jdbcTemplate.update(
            """
                insert into profile_safety_events (id, profile_id, safety_state, reason)
                values (?, ?, ?, ?)
                """,
            eventId,
            profileId,
            safetyState,
            reason
        );
    }

    public void replaceInterests(UUID profileId, List<ProfileInterestRequest> interests) {
        jdbcTemplate.update("delete from profile_interests where profile_id = ?", profileId);
        for (ProfileInterestRequest interest : interests) {
            jdbcTemplate.update(
                """
                    insert into profile_interests (profile_id, interest_key, interest_value, weight)
                    values (?, ?, ?, ?)
                    """,
                profileId,
                interest.interestKey().trim(),
                interest.interestValue().trim(),
                interest.weight()
            );
        }
    }

    public List<ProfileInterestResponse> findInterests(UUID profileId) {
        return jdbcTemplate.query(
            """
                select interest_key, interest_value, weight
                from profile_interests
                where profile_id = ?
                order by interest_key, interest_value
                """,
            (rs, rowNum) -> new ProfileInterestResponse(
                rs.getString("interest_key"),
                rs.getString("interest_value"),
                rs.getBigDecimal("weight")
            ),
            profileId
        );
    }

    public ProfileLocationResponse upsertLocation(UUID profileId, UpdateProfileLocationRequest request) {
        return jdbcTemplate.queryForObject(
            """
                insert into profile_locations (
                    profile_id, approximate_point, precision_km, city, region, country
                )
                values (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?, ?)
                on conflict (profile_id)
                do update set approximate_point = excluded.approximate_point,
                    precision_km = excluded.precision_km,
                    city = excluded.city,
                    region = excluded.region,
                    country = excluded.country,
                    updated_at = now()
                returning city, region, country, precision_km, updated_at
                """,
            this::mapLocation,
            profileId,
            request.longitude(),
            request.latitude(),
            request.precisionKm(),
            trimToNull(request.city()),
            trimToNull(request.region()),
            trimToNull(request.country())
        );
    }

    public Optional<ProfileLocationResponse> findLocation(UUID profileId) {
        List<ProfileLocationResponse> locations = jdbcTemplate.query(
            """
                select city, region, country, precision_km, updated_at
                from profile_locations
                where profile_id = ?
                """,
            this::mapLocation,
            profileId
        );
        return locations.stream().findFirst();
    }

    public void upsertEmbeddingVersion(UUID versionId, String versionName, String modelName) {
        jdbcTemplate.update(
            """
                insert into profile_embedding_versions (id, version_name, model_name, dimensions)
                values (?, ?, ?, 384)
                on conflict (version_name) do nothing
                """,
            versionId,
            versionName,
            modelName
        );
    }

    public UUID findEmbeddingVersionId(String versionName) {
        return jdbcTemplate.queryForObject(
            "select id from profile_embedding_versions where version_name = ?",
            UUID.class,
            versionName
        );
    }

    public void upsertEmbedding(UUID profileId, UUID embeddingVersionId, String vectorLiteral) {
        jdbcTemplate.update(
            "update profile_embeddings set is_active = false where profile_id = ?",
            profileId
        );
        jdbcTemplate.update(
            """
                insert into profile_embeddings (profile_id, embedding_version_id, embedding, is_active)
                values (?, ?, ?::vector, true)
                on conflict (profile_id, embedding_version_id)
                do update set embedding = excluded.embedding, is_active = true, created_at = now()
                """,
            profileId,
            embeddingVersionId,
            vectorLiteral
        );
    }

    public void updateEmbeddingStatus(UUID profileId, String embeddingStatus) {
        jdbcTemplate.update(
            "update profiles set embedding_status = ?, updated_at = now() where id = ?",
            embeddingStatus,
            profileId
        );
    }

    public void markEmbeddingStaleIfCurrent(UUID profileId) {
        jdbcTemplate.update(
            "update profiles set embedding_status = 'STALE', updated_at = now() where id = ? and embedding_status = 'CURRENT'",
            profileId
        );
    }

    public ProfileEmbeddingStatusResponse embeddingStatus(UUID profileId) {
        return jdbcTemplate.queryForObject(
            """
                select p.id as profile_id, p.embedding_status, v.version_name, v.model_name, v.dimensions, e.created_at
                from profiles p
                left join profile_embeddings e on e.profile_id = p.id and e.is_active
                left join profile_embedding_versions v on v.id = e.embedding_version_id
                where p.id = ?
                """,
            (rs, rowNum) -> new ProfileEmbeddingStatusResponse(
                rs.getObject("profile_id", UUID.class),
                rs.getString("embedding_status"),
                rs.getString("version_name"),
                rs.getString("model_name"),
                (Integer) rs.getObject("dimensions"),
                rs.getObject("created_at", OffsetDateTime.class)
            ),
            profileId
        );
    }

    public ProfileSafetyStateResponse safetyState(UUID profileId) {
        return jdbcTemplate.queryForObject(
            """
                select profile_id, safety_state, reason, updated_at
                from profile_safety_states
                where profile_id = ?
                """,
            (rs, rowNum) -> new ProfileSafetyStateResponse(
                rs.getObject("profile_id", UUID.class),
                rs.getString("safety_state"),
                rs.getString("reason"),
                rs.getObject("updated_at", OffsetDateTime.class)
            ),
            profileId
        );
    }

    public void updateCompletenessScore(UUID profileId, BigDecimal completenessScore) {
        jdbcTemplate.update(
            "update profiles set profile_completeness_score = ?, updated_at = now() where id = ?",
            completenessScore,
            profileId
        );
    }

    private ProfileResponse mapProfileWithoutChildren(ResultSet rs, int rowNum) throws SQLException {
        return new ProfileResponse(
            rs.getObject("id", UUID.class),
            rs.getString("external_ref"),
            rs.getString("display_name"),
            rs.getString("profile_type"),
            rs.getString("status"),
            rs.getString("bio"),
            rs.getString("city"),
            rs.getString("region"),
            rs.getString("country"),
            rs.getObject("last_active_at", OffsetDateTime.class),
            rs.getBigDecimal("profile_completeness_score"),
            rs.getString("embedding_status"),
            List.of(),
            null,
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private ProfileLocationResponse mapLocation(ResultSet rs, int rowNum) throws SQLException {
        return new ProfileLocationResponse(
            rs.getString("city"),
            rs.getString("region"),
            rs.getString("country"),
            rs.getBigDecimal("precision_km"),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
