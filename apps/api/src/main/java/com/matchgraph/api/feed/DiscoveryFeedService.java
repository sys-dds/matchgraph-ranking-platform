package com.matchgraph.api.feed;

import java.util.List;
import java.util.UUID;

import com.matchgraph.api.features.FeatureSnapshotRun;
import com.matchgraph.api.features.FeatureSnapshotService;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.ranking.RankingDecision;
import com.matchgraph.api.ranking.RankingService;
import com.matchgraph.api.retrieval.CandidateRetrievalRun;
import com.matchgraph.api.retrieval.CandidateRetrievalService;
import com.matchgraph.api.retrieval.RunRetrievalRequest;

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

    public DiscoveryFeedService(
        FeedRepository feedRepository,
        CandidateRetrievalService candidateRetrievalService,
        FeatureSnapshotService featureSnapshotService,
        RankingService rankingService,
        ProfileService profileService
    ) {
        this.feedRepository = feedRepository;
        this.candidateRetrievalService = candidateRetrievalService;
        this.featureSnapshotService = featureSnapshotService;
        this.rankingService = rankingService;
        this.profileService = profileService;
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
        RankingDecision rankingDecision = rankingService.run(profileId, snapshotRun.id(), "v1_balanced", limit, "FEED_REFRESH");
        UUID feedSnapshotId = feedRepository.createSnapshot(profileId, rankingDecision);
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

    public FeedPage read(UUID profileId, int limit, String cursor) {
        profileService.requireExists(profileId);
        int sanitizedLimit = sanitizeLimit(limit);
        int afterPosition = parseCursor(cursor);
        FeedSnapshot snapshot = feedRepository.activeSnapshot(profileId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "active discovery feed not found"));
        List<FeedItem> items = feedRepository.page(snapshot.id(), afterPosition, sanitizedLimit);
        String nextCursor = items.size() < sanitizedLimit || items.isEmpty()
            ? null
            : String.valueOf(items.getLast().position());
        return new FeedPage(items, nextCursor);
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
