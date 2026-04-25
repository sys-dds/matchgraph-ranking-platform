package com.matchgraph.api.embedding;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class EmbeddingLifecycleController {

    private final EmbeddingLifecycleService embeddingLifecycleService;

    public EmbeddingLifecycleController(EmbeddingLifecycleService embeddingLifecycleService) {
        this.embeddingLifecycleService = embeddingLifecycleService;
    }

    @PostMapping("/profiles/{profileId}/embedding/refresh-request")
    public EmbeddingRefreshRequest request(@PathVariable UUID profileId, @RequestBody EmbeddingRefreshRequestBody request) {
        return embeddingLifecycleService.request(profileId, request);
    }

    @PostMapping("/embeddings/refresh-batches")
    public EmbeddingRefreshBatch createBatch(@RequestBody(required = false) CreateEmbeddingRefreshBatchRequest request) {
        return embeddingLifecycleService.createBatch(request);
    }

    @GetMapping("/embeddings/refresh-batches/{batchId}")
    public EmbeddingRefreshBatch getBatch(@PathVariable UUID batchId) {
        return embeddingLifecycleService.getBatch(batchId);
    }

    @PostMapping("/embeddings/refresh-batches/{batchId}/complete")
    public EmbeddingRefreshBatch completeBatch(@PathVariable UUID batchId, @RequestBody CompleteEmbeddingRefreshBatchRequest request) {
        return embeddingLifecycleService.completeBatch(batchId, request);
    }
}
