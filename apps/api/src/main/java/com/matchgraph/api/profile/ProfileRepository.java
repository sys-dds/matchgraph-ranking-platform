package com.matchgraph.api.profile;

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

    public ProfileResponse create(CreateProfileRequest request) {
        UUID id = UUID.randomUUID();
        return jdbcTemplate.queryForObject(
            """
                insert into profiles (id, external_ref, display_name, profile_type, status)
                values (?, ?, ?, ?, ?)
                returning id, external_ref, display_name, profile_type, status, created_at, updated_at
                """,
            this::mapProfile,
            id,
            request.externalRef().trim(),
            request.displayName().trim(),
            request.profileType(),
            request.status()
        );
    }

    public Optional<ProfileResponse> findById(UUID id) {
        List<ProfileResponse> profiles = jdbcTemplate.query(
            """
                select id, external_ref, display_name, profile_type, status, created_at, updated_at
                from profiles
                where id = ?
                """,
            this::mapProfile,
            id
        );
        return profiles.stream().findFirst();
    }

    public List<ProfileResponse> find(String profileType, String status, int limit) {
        String typeFilter = profileType == null ? null : profileType;
        String statusFilter = status == null ? null : status;
        return jdbcTemplate.query(
            """
                select id, external_ref, display_name, profile_type, status, created_at, updated_at
                from profiles
                where (? is null or profile_type = ?)
                  and (? is null or status = ?)
                order by created_at desc, id
                limit ?
                """,
            this::mapProfile,
            typeFilter,
            typeFilter,
            statusFilter,
            statusFilter,
            limit
        );
    }

    public boolean exists(UUID id) {
        Boolean exists = jdbcTemplate.queryForObject("select exists (select 1 from profiles where id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(exists);
    }

    private ProfileResponse mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new ProfileResponse(
            rs.getObject("id", UUID.class),
            rs.getString("external_ref"),
            rs.getString("display_name"),
            rs.getString("profile_type"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
