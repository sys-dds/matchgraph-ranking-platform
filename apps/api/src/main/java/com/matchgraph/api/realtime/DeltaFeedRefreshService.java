package com.matchgraph.api.realtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.matchgraph.api.feed.FeedItem;
import com.matchgraph.api.feed.FeedRepository;
import com.matchgraph.api.realtime.RealtimeModels.DeltaFeedRefreshItem;
import com.matchgraph.api.realtime.RealtimeModels.DeltaFeedRefreshRequest;
import com.matchgraph.api.realtime.RealtimeModels.DeltaFeedRefreshRun;
import com.matchgraph.api.serving.MultiSurfaceRecommendationService;
import com.matchgraph.api.serving.ServingModels.MultiStageServingRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeltaFeedRefreshService {

    private final FeedRepository feedRepository;
    private final DeltaFeedRefreshRepository repository;
    private final MultiSurfaceRecommendationService recommendationService;

    public DeltaFeedRefreshService(FeedRepository feedRepository, DeltaFeedRefreshRepository repository, MultiSurfaceRecommendationService recommendationService) {
        this.feedRepository = feedRepository;
        this.repository = repository;
        this.recommendationService = recommendationService;
    }

    @Transactional
    public DeltaFeedRefreshRun refresh(UUID profileId, UUID feedSnapshotId, DeltaFeedRefreshRequest request) {
        DeltaFeedRefreshRequest effective = request == null ? new DeltaFeedRefreshRequest(null, null, null, null, null) : request;
        List<FeedItem> existing = feedSnapshotId == null ? List.of() : feedRepository.page(feedSnapshotId, 0, 100);
        Set<UUID> invalidated = new LinkedHashSet<>(repository.invalidatedCandidates(profileId));
        List<UUID> removed = existing.stream()
            .filter(item -> invalidated.contains(item.candidateProfileId()))
            .map(FeedItem::candidateProfileId)
            .toList();
        Set<UUID> existingIds = new LinkedHashSet<>(existing.stream().map(FeedItem::candidateProfileId).toList());
        int wanted = effective.maxNewItems() == null ? Math.max(1, removed.size()) : Math.max(1, effective.maxNewItems());
        var next = recommendationService.multiStage(profileId, "HOME_FEED", new MultiStageServingRequest(effective.sessionId(), wanted + existing.size(), null, false, false, false));
        List<UUID> created = next.servedItems().stream()
            .map(item -> item.candidateProfileId())
            .filter(candidate -> !invalidated.contains(candidate))
            .filter(candidate -> !existingIds.contains(candidate))
            .limit(wanted)
            .toList();
        List<UUID> unchanged = existing.stream()
            .filter(item -> !removed.contains(item.candidateProfileId()))
            .map(FeedItem::candidateProfileId)
            .toList();
        List<UUID> moved = new ArrayList<>();
        List<DeltaFeedRefreshItem> items = new ArrayList<>();
        for (FeedItem item : existing) {
            if (removed.contains(item.candidateProfileId())) {
                items.add(new DeltaFeedRefreshItem(item.candidateProfileId(), "REMOVED", item.position(), null, Map.of("reason", "candidate invalidated by realtime feedback")));
            } else {
                items.add(new DeltaFeedRefreshItem(item.candidateProfileId(), "UNCHANGED", item.position(), item.position(), Map.of("reason", "still eligible")));
            }
        }
        int newPosition = existing.size() + 1;
        for (UUID candidate : created) {
            items.add(new DeltaFeedRefreshItem(candidate, "NEW", null, newPosition++, Map.of("reason", "replacement from multi-stage serving", "traceId", next.traceId())));
        }
        boolean degraded = next.degraded() || (removed.size() > created.size());
        String reason = effective.reason() == null ? "delta refresh applied invalidations and replacement serving; not a silent full rebuild" : effective.reason();
        UUID runId = repository.createRun(profileId, feedSnapshotId, effective.servingRequestId(), effective.sessionId(), effective.triggerEventId(), removed.size(), created.size(), moved.size(), unchanged.size(), degraded, next.traceId(), Map.of("reason", reason, "invalidatedCandidates", invalidated));
        items.forEach(item -> repository.insertItem(runId, item));
        return new DeltaFeedRefreshRun(runId, removed, created, moved, unchanged, degraded, reason, next.traceId());
    }
}
