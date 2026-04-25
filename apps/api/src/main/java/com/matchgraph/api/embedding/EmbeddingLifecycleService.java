package com.matchgraph.api.embedding;

import java.util.UUID;

import com.matchgraph.api.profile.ProfileEmbeddingStatusResponse;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.profile.UpsertProfileEmbeddingRequest;
import com.matchgraph.api.shared.cache.OnlineServingCacheService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmbeddingLifecycleService {

    private final EmbeddingLifecycleRepository embeddingLifecycleRepository;
    private final ProfileService profileService;
    private final OnlineServingCacheService cacheService;

    public EmbeddingLifecycleService(
        EmbeddingLifecycleRepository embeddingLifecycleRepository,
        ProfileService profileService,
        OnlineServingCacheService cacheService
    ) {
        this.embeddingLifecycleRepository = embeddingLifecycleRepository;
        this.profileService = profileService;
        this.cacheService = cacheService;
    }

    @Transactional
    public EmbeddingRefreshRequest request(UUID profileId, EmbeddingRefreshRequestBody request) {
        profileService.requireExists(profileId);
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
        }
        return embeddingLifecycleRepository.createRequest(
            UUID.randomUUID(),
            profileId,
            request.reason().trim(),
            request.requestedBy(),
            embeddingLifecycleRepository.embeddingFact(profileId)
        );
    }

    @Transactional
    public EmbeddingRefreshBatch createBatch(CreateEmbeddingRefreshBatchRequest request) {
        int maxItems = request == null || request.maxItems() == null ? 25 : request.maxItems();
        if (maxItems < 1 || maxItems > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxItems must be between 1 and 500");
        }
        String reason = request == null || request.selectionReason() == null || request.selectionReason().isBlank()
            ? "STALE_OR_REQUESTED"
            : request.selectionReason().trim();
        EmbeddingRefreshBatch batch = embeddingLifecycleRepository.createBatch(UUID.randomUUID(), maxItems, reason);
        for (EmbeddingRefreshRequest refreshRequest : embeddingLifecycleRepository.refreshCandidates(maxItems)) {
            embeddingLifecycleRepository.addBatchItem(batch.id(), refreshRequest);
        }
        return getBatch(batch.id());
    }

    public EmbeddingRefreshBatch getBatch(UUID batchId) {
        return embeddingLifecycleRepository.batch(batchId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "embedding refresh batch not found"));
    }

    @Transactional
    public EmbeddingRefreshBatch completeBatch(UUID batchId, CompleteEmbeddingRefreshBatchRequest request) {
        if (request == null || request.items() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "items are required");
        }
        for (CompleteEmbeddingRefreshBatchRequest.CompletedEmbeddingRefreshItem item : request.items()) {
            ProfileEmbeddingStatusResponse status = profileService.upsertEmbedding(
                item.profileId(),
                new UpsertProfileEmbeddingRequest(item.versionName(), item.modelName(), item.embedding())
            );
            embeddingLifecycleRepository.completeItem(batchId, item.profileId(), status.activeVersionName());
            cacheService.invalidateFeed(item.profileId());
        }
        embeddingLifecycleRepository.completeBatch(batchId);
        return getBatch(batchId);
    }
}
