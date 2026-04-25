package com.matchgraph.api.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.shared.cache.OnlineServingCacheService;
import com.matchgraph.api.streaming.StreamingModels.CacheInvalidationAction;
import com.matchgraph.api.streaming.StreamingModels.CacheInvalidationNode;
import com.matchgraph.api.streaming.StreamingModels.CacheInvalidationRun;

import org.springframework.stereotype.Service;

@Service
public class CacheInvalidationGraphService {

    private final CacheInvalidationGraphRepository repository;
    private final OnlineServingCacheService cacheService;

    public CacheInvalidationGraphService(CacheInvalidationGraphRepository repository, OnlineServingCacheService cacheService) {
        this.repository = repository;
        this.cacheService = cacheService;
    }

    public Map<String, Object> build() {
        CacheInvalidationNode profile = repository.upsertNode("PROFILE", "demo-profile", Map.of("seed", true));
        CacheInvalidationNode feed = repository.upsertNode("FEED_SNAPSHOT", "demo-feed", Map.of("seed", true));
        CacheInvalidationNode features = repository.upsertNode("FEATURE_KEY", "pair_features", Map.of("seed", true));
        repository.edge(profile, feed, "OWNS_FEED");
        repository.edge(profile, features, "USES_FEATURES");
        return Map.of("status", "COMPLETED", "seedGraph", true);
    }

    public CacheInvalidationRun invalidate(String nodeType, String nodeRef, boolean global) {
        List<CacheInvalidationNode> affected = repository.affected(nodeType, nodeRef);
        if (affected.isEmpty()) {
            affected = List.of(repository.upsertNode(nodeType, nodeRef, Map.of("createdByInvalidation", true)));
        }
        List<CacheInvalidationAction> actions = new ArrayList<>();
        for (CacheInvalidationNode node : affected) {
            actions.add(action(node));
        }
        return repository.saveRun(nodeType, nodeRef, global, actions, Map.of("affectedCount", affected.size(), "globalInvalidation", global, "preciseByDefault", !global));
    }

    public CacheInvalidationRun run(UUID runId) {
        return repository.run(runId);
    }

    public List<CacheInvalidationNode> affected(String nodeType, String nodeRef) {
        return repository.affected(nodeType, nodeRef);
    }

    private CacheInvalidationAction action(CacheInvalidationNode node) {
        String actionType = switch (node.nodeType()) {
            case "PROFILE", "FEED_SNAPSHOT" -> "INVALIDATE_FEED";
            case "FEATURE_KEY", "CANDIDATE" -> "INVALIDATE_FEATURES";
            case "MODEL_VERSION" -> "INVALIDATE_MODEL_CACHE";
            case "SOURCE" -> "INVALIDATE_CANDIDATE_POOL";
            default -> "INVALIDATE_SURFACE_CACHE";
        };
        String status = "NOT_SUPPORTED";
        if ("PROFILE".equals(node.nodeType())) {
            try {
                cacheService.invalidateFeed(UUID.fromString(node.nodeRef()));
                status = "EXECUTED";
            } catch (IllegalArgumentException ignored) {
                status = "NOT_SUPPORTED";
            }
        }
        return new CacheInvalidationAction(UUID.randomUUID(), actionType, node.nodeType(), node.nodeRef(), status, Map.of("targeted", true, "globalClear", false));
    }
}
