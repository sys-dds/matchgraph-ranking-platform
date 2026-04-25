package com.matchgraph.api.embedding;

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
public class EmbeddingLifecycleRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EmbeddingLifecycleRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public EmbeddingRefreshRequest createRequest(UUID id, UUID profileId, String reason, String requestedBy, EmbeddingFact fact) {
        return jdbcTemplate.queryForObject(
            """
                insert into embedding_refresh_requests (
                    id, profile_id, status, reason, requested_by, current_embedding_status, current_embedding_version
                )
                values (?, ?, 'REQUESTED', ?, ?, ?, ?)
                returning id, profile_id, status, reason, requested_by, current_embedding_status,
                    current_embedding_version, created_at, updated_at
                """,
            this::mapRequest,
            id,
            profileId,
            reason,
            requestedBy,
            fact.status(),
            fact.version()
        );
    }

    public EmbeddingFact embeddingFact(UUID profileId) {
        return jdbcTemplate.queryForObject(
            """
                select p.embedding_status, v.version_name
                from profiles p
                left join profile_embeddings e on e.profile_id = p.id and e.is_active
                left join profile_embedding_versions v on v.id = e.embedding_version_id
                where p.id = ?
                """,
            (rs, rowNum) -> new EmbeddingFact(rs.getString("embedding_status"), rs.getString("version_name")),
            profileId
        );
    }

    public List<EmbeddingRefreshRequest> refreshCandidates(int limit) {
        jdbcTemplate.update(
            """
                insert into embedding_refresh_requests (
                    id, profile_id, status, reason, requested_by,
                    current_embedding_status, current_embedding_version
                )
                select gen_random_uuid(), p.id, 'REQUESTED', 'STALE_PROFILE', null,
                    p.embedding_status, v.version_name
                from profiles p
                left join profile_embeddings e on e.profile_id = p.id and e.is_active
                left join profile_embedding_versions v on v.id = e.embedding_version_id
                where p.embedding_status = 'STALE'
                  and not exists (
                    select 1 from embedding_refresh_requests r
                    where r.profile_id = p.id and r.status in ('REQUESTED', 'PLANNED')
                  )
                """
        );
        return jdbcTemplate.query(
            """
                select id, profile_id, status, reason, requested_by, current_embedding_status,
                    current_embedding_version, created_at, updated_at
                from embedding_refresh_requests
                where status = 'REQUESTED'
                order by created_at
                limit ?
                """,
            this::mapRequest,
            limit
        );
    }

    public EmbeddingRefreshBatch createBatch(UUID id, int maxItems, String selectionReason) {
        return jdbcTemplate.queryForObject(
            """
                insert into embedding_refresh_batches (id, status, max_items, selection_reason)
                values (?, 'CREATED', ?, ?)
                returning id, status, max_items, selection_reason, created_at, completed_at,
                    metadata_json::text as metadata_json
                """,
            this::mapBatchWithoutItems,
            id,
            maxItems,
            selectionReason
        );
    }

    public void addBatchItem(UUID batchId, EmbeddingRefreshRequest request) {
        jdbcTemplate.update(
            """
                insert into embedding_refresh_batch_items (
                    id, batch_id, request_id, profile_id, status, current_embedding_status,
                    current_embedding_version, requested_reason
                )
                values (?, ?, ?, ?, 'PENDING', ?, ?, ?)
                on conflict (batch_id, profile_id) do nothing
                """,
            UUID.randomUUID(),
            batchId,
            request.id(),
            request.profileId(),
            request.currentEmbeddingStatus(),
            request.currentEmbeddingVersion(),
            request.reason()
        );
        jdbcTemplate.update("update embedding_refresh_requests set status = 'PLANNED', updated_at = now() where id = ?", request.id());
    }

    public Optional<EmbeddingRefreshBatch> batch(UUID batchId) {
        return jdbcTemplate.query(
            """
                select id, status, max_items, selection_reason, created_at, completed_at,
                    metadata_json::text as metadata_json
                from embedding_refresh_batches
                where id = ?
                """,
            this::mapBatchWithoutItems,
            batchId
        ).stream().findFirst().map(batch -> new EmbeddingRefreshBatch(
            batch.id(),
            batch.status(),
            batch.maxItems(),
            batch.selectionReason(),
            batch.createdAt(),
            batch.completedAt(),
            batch.metadata(),
            items(batch.id())
        ));
    }

    public List<EmbeddingRefreshBatchItem> items(UUID batchId) {
        return jdbcTemplate.query(
            """
                select id, batch_id, request_id, profile_id, status, current_embedding_status,
                    current_embedding_version, requested_reason, completed_embedding_version,
                    completed_at, created_at
                from embedding_refresh_batch_items
                where batch_id = ?
                order by created_at, profile_id
                """,
            this::mapItem,
            batchId
        );
    }

    public void completeItem(UUID batchId, UUID profileId, String completedVersion) {
        jdbcTemplate.update(
            """
                update embedding_refresh_batch_items
                set status = 'COMPLETED', completed_embedding_version = ?, completed_at = now()
                where batch_id = ? and profile_id = ?
                """,
            completedVersion,
            batchId,
            profileId
        );
        jdbcTemplate.update(
            """
                update embedding_refresh_requests
                set status = 'COMPLETED', updated_at = now()
                where profile_id = ? and status in ('REQUESTED', 'PLANNED')
                """,
            profileId
        );
    }

    public void completeBatch(UUID batchId) {
        jdbcTemplate.update("update embedding_refresh_batches set status = 'COMPLETED', completed_at = now() where id = ?", batchId);
    }

    private EmbeddingRefreshRequest mapRequest(ResultSet rs, int rowNum) throws SQLException {
        return new EmbeddingRefreshRequest(
            rs.getObject("id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getString("status"),
            rs.getString("reason"),
            rs.getString("requested_by"),
            rs.getString("current_embedding_status"),
            rs.getString("current_embedding_version"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private EmbeddingRefreshBatch mapBatchWithoutItems(ResultSet rs, int rowNum) throws SQLException {
        return new EmbeddingRefreshBatch(
            rs.getObject("id", UUID.class),
            rs.getString("status"),
            rs.getInt("max_items"),
            rs.getString("selection_reason"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            map(rs.getString("metadata_json")),
            List.of()
        );
    }

    private EmbeddingRefreshBatchItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new EmbeddingRefreshBatchItem(
            rs.getObject("id", UUID.class),
            rs.getObject("batch_id", UUID.class),
            rs.getObject("request_id", UUID.class),
            rs.getObject("profile_id", UUID.class),
            rs.getString("status"),
            rs.getString("current_embedding_status"),
            rs.getString("current_embedding_version"),
            rs.getString("requested_reason"),
            rs.getString("completed_embedding_version"),
            rs.getObject("completed_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored embedding lifecycle json is invalid", exception);
        }
    }

    public record EmbeddingFact(String status, String version) {
    }
}
