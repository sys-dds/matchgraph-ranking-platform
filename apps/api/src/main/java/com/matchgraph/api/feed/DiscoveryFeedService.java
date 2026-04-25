package com.matchgraph.api.feed;

import java.util.List;
import java.util.UUID;

import com.matchgraph.api.experiment.ExperimentService;
import com.matchgraph.api.experiment.RankingExperimentAssignment;
import com.matchgraph.api.features.FeatureSnapshotRun;
import com.matchgraph.api.features.FeatureSnapshotService;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.ranking.RankingDecision;
import com.matchgraph.api.ranking.RankingService;
import com.matchgraph.api.retrieval.CandidateRetrievalRun;
import com.matchgraph.api.retrieval.CandidateRetrievalService;
import com.matchgraph.api.retrieval.HardExclusionService;
import com.matchgraph.api.retrieval.RunRetrievalRequest;
import com.matchgraph.api.shared.cache.OnlineServingCacheService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DiscoveryFeedService {

    private static final int DEFAULT_LIMIT = 20;

    private final FeedRepository feedRepository;
    private final CandidateRetrievalService candidateRetrievalService;
    private final FeatureSnapshotService featureSnapshotService;
    private final RankingService rankingService;
    private final ProfileService profileService;
    private final ExperimentService experimentService;
    private final OnlineServingCacheService cacheService;
    private final HardExclusionService hardExclusionService;

    public DiscoveryFeedService(
        FeedRepository feedRepository,
        CandidateRetrievalService candidateRetrievalService,
        FeatureSnapshotService featureSnapshotService,
        RankingService rankingService,
        ProfileService profileService,
        ExperimentService experimentService,
        OnlineServingCacheService cacheService,
        HardExclusionService hardExclusionService
    ) {
        this.feedRepository = feedRepository;
        this.candidateRetrievalService = candidateRetrievalService;
        this.featureSnapshotService = featureSnapshotService;
        this.rankingService = rankingService;
        this.profileService = profileService;
        this.experimentService = experimentService;
        this.cacheService = cacheService;
        this.hardExclusionService = hardExclusionService;
    }

    @Transactional
    public FeedSnapshot refresh(UUID profileId, FeedRefreshRequest request) {
        profileService.requireExists(profileId);
        int limit = sanitizeLimit(request == null ? null : request.limit());
        UUID retrievalRunId = request == null ? null : request.retrievalRunId();
        CandidateRetrievalRun retrievalRun = retrievalRunId == null
            ? candidateRetrievalService.run(profileId, new RunRetrievalRequest(limit, null, null))
            : candidateRetrievalService.get(profileId, retrievalRunId);
        FeatureSnapshotRun snapshotRun = featureSnapshotService.createFromRetrieval(profileId, retrievalRun.id());
        RankingExperimentAssignment assignment = assignment(profileId, request == null ? null : request.experimentKey());
        String rankingVersion = assignment == null ? "v1_balanced" : assignment.assignedRankingVersion();
        RankingDecision rankingDecision = rankingService.run(
            profileId,
            snapshotRun.id(),
            rankingVersion,
            limit,
            "FEED_REFRESH",
            assignment == null ? null : assignment.experimentKey(),
            assignment == null ? null : assignment.assignedVariantKey(),
            assignment == null ? null : assignment.id(),
            null
        );
        UUID feedSnapshotId = feedRepository.createSnapshot(profileId, rankingDecision);
        cacheService.invalidateFeed(profileId);
        for (var item : rankingDecision.items()) {
            feedRepository.insertItem(feedSnapshotId, rankingDecision, item);
        }
        List<FeedItem> items = feedRepository.page(feedSnapshotId, 0, limit);
        return feedRepository.findSnapshot(profileId, feedSnapshotId)
            .map(snapshot -> new FeedSnapshot(
                snapshot.id(),
                snapshot.profileId(),
                snapshot.retrievalRunId(),
                snapshot.featureSnapshotRunId(),
                snapshot.rankingDecisionLogId(),
                snapshot.rankingVersion(),
                snapshot.status(),
                snapshot.createdAt(),
                items
            ))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "feed snapshot was not persisted"));
    }

    private RankingExperimentAssignment assignment(UUID profileId, String experimentKey) {
        if (experimentKey == null || experimentKey.isBlank()) {
            return null;
        }
        return experimentService.assign(profileId, experimentKey.trim());
    }

    public FeedPage read(UUID profileId, int limit, String cursor) {
        profileService.requireExists(profileId);
        int sanitizedLimit = sanitizeLimit(limit);
        String cacheKey = cacheService.feedPageKey(profileId, sanitizedLimit, cursor);
        return cacheService.get(cacheKey, FeedPage.class)
            .filter(page -> cachedPageStillVisible(profileId, page, cacheKey))
            .map(page -> new FeedPage(page.items(), page.nextCursor(), java.util.Map.of("cacheHit", true, "cacheCategory", "feedPage")))
            .orElseGet(() -> readAndCache(profileId, sanitizedLimit, cursor, cacheKey));
    }

    private FeedPage readAndCache(UUID profileId, int sanitizedLimit, String cursor, String cacheKey) {
        int afterPosition = parseCursor(cursor);
        FeedSnapshot snapshot = feedRepository.activeSnapshot(profileId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "active discovery feed not found"));
        List<FeedItem> items = visibleItems(profileId, feedRepository.page(snapshot.id(), afterPosition, sanitizedLimit));
        String nextCursor = items.size() < sanitizedLimit || items.isEmpty()
            ? null
            : String.valueOf(items.getLast().position());
        FeedPage page = new FeedPage(items, nextCursor, java.util.Map.of("cacheHit", false, "cacheCategory", "feedPage"));
        cacheService.putFeed(cacheKey, page);
        cacheService.putFeed(cacheService.activeFeedKey(profileId), snapshot.id());
        return page;
    }

    private boolean cachedPageStillVisible(UUID profileId, FeedPage page, String cacheKey) {
        boolean visible = visibleItems(profileId, page.items()).size() == page.items().size();
        if (!visible) {
            cacheService.invalidateFeed(profileId);
        }
        return visible;
    }

    private List<FeedItem> visibleItems(UUID profileId, List<FeedItem> items) {
        return items.stream()
            .filter(item -> hardExclusionService.exclusionReason(profileId, item.candidateProfileId()).isEmpty())
            .toList();
    }

    private int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(cursor);
            if (parsed < 0) {
                throw new NumberFormatException("negative cursor");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor must be a non-negative position");
        }
    }

    private int sanitizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        return limit;
    }
}
