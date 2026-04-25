package com.matchgraph.api.scale;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.evaluation.OfflineEvaluationRequest;
import com.matchgraph.api.evaluation.OfflineEvaluationService;
import com.matchgraph.api.features.FeatureSnapshotRun;
import com.matchgraph.api.features.FeatureSnapshotService;
import com.matchgraph.api.feed.DiscoveryFeedService;
import com.matchgraph.api.feed.FeedPage;
import com.matchgraph.api.feed.FeedRefreshRequest;
import com.matchgraph.api.ranking.RankingService;
import com.matchgraph.api.retrieval.CandidateRetrievalRun;
import com.matchgraph.api.retrieval.CandidateRetrievalService;
import com.matchgraph.api.retrieval.RunRetrievalRequest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ScaleBenchmarkService {

    private final ScaleRepository scaleRepository;
    private final CandidateRetrievalService retrievalService;
    private final FeatureSnapshotService featureSnapshotService;
    private final RankingService rankingService;
    private final DiscoveryFeedService feedService;
    private final OfflineEvaluationService offlineEvaluationService;

    public ScaleBenchmarkService(
        ScaleRepository scaleRepository,
        CandidateRetrievalService retrievalService,
        FeatureSnapshotService featureSnapshotService,
        RankingService rankingService,
        DiscoveryFeedService feedService,
        OfflineEvaluationService offlineEvaluationService
    ) {
        this.scaleRepository = scaleRepository;
        this.retrievalService = retrievalService;
        this.featureSnapshotService = featureSnapshotService;
        this.rankingService = rankingService;
        this.feedService = feedService;
        this.offlineEvaluationService = offlineEvaluationService;
    }

    @Transactional
    public RankingBenchmarkResponse benchmark(RankingBenchmarkRequest request) {
        int sampleCount = request == null || request.sampleProfileCount() == null ? 5 : request.sampleProfileCount();
        if (sampleCount < 1 || sampleCount > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sampleProfileCount must be between 1 and 100");
        }
        RankingBenchmarkRequest normalized = request == null ? new RankingBenchmarkRequest(null, sampleCount, false, false) : request;
        RankingBenchmarkRun run = scaleRepository.createBenchmarkRun(UUID.randomUUID(), normalized, sampleCount);
        for (UUID profileId : scaleRepository.sampleProfiles(sampleCount)) {
            long start = System.nanoTime();
            CandidateRetrievalRun retrieval = retrievalService.run(profileId, new RunRetrievalRequest(20, null, null));
            long retrievalMs = elapsed(start);
            start = System.nanoTime();
            FeatureSnapshotRun snapshot = featureSnapshotService.createFromRetrieval(profileId, retrieval.id());
            long snapshotMs = elapsed(start);
            start = System.nanoTime();
            rankingService.run(profileId, snapshot.id(), "v1_balanced", 20, "SCALE_BENCHMARK");
            long rankingMs = elapsed(start);
            start = System.nanoTime();
            feedService.refresh(profileId, new FeedRefreshRequest(retrieval.id(), 20));
            long feedMs = elapsed(start);
            CacheCounts cacheCounts = cacheCounts(profileId, Boolean.TRUE.equals(normalized.cacheEnabled()));
            Long evaluationMs = null;
            if (Boolean.TRUE.equals(normalized.includeOfflineEvaluation())) {
                start = System.nanoTime();
                offlineEvaluationService.evaluate(new OfflineEvaluationRequest("v1_balanced", null, null, 10, null));
                evaluationMs = elapsed(start);
            }
            scaleRepository.insertBenchmarkResult(
                run.id(),
                profileId,
                retrievalMs,
                snapshotMs,
                rankingMs,
                feedMs,
                evaluationMs,
                retrieval.finalCandidateCount(),
                cacheCounts.hits(),
                cacheCounts.misses(),
                Map.of(
                    "benchmarkType", "deterministic local ranking benchmark",
                    "retrievalRunId", retrieval.id().toString(),
                    "featureSnapshotRunId", snapshot.id().toString(),
                    "rankingVersion", "v1_balanced",
                    "candidateCount", retrieval.finalCandidateCount(),
                    "cacheChecked", Boolean.TRUE.equals(normalized.cacheEnabled()),
                    "offlineEvaluationIncluded", Boolean.TRUE.equals(normalized.includeOfflineEvaluation())
                )
            );
        }
        scaleRepository.completeBenchmarkRun(run.id());
        return get(run.id());
    }

    public RankingBenchmarkResponse get(UUID benchmarkRunId) {
        RankingBenchmarkRun run = scaleRepository.benchmarkRun(benchmarkRunId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ranking benchmark run not found"));
        return new RankingBenchmarkResponse(run, scaleRepository.benchmarkResults(benchmarkRunId));
    }

    private long elapsed(long startNanos) {
        return java.time.Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private CacheCounts cacheCounts(UUID profileId, boolean enabled) {
        if (!enabled) {
            return new CacheCounts(0, 0);
        }
        FeedPage first = feedService.read(profileId, 20, null);
        FeedPage second = feedService.read(profileId, 20, null);
        return new CacheCounts(cacheHit(first) + cacheHit(second), cacheMiss(first) + cacheMiss(second));
    }

    private int cacheHit(FeedPage page) {
        return Boolean.TRUE.equals(page.cacheMetadata().get("cacheHit")) ? 1 : 0;
    }

    private int cacheMiss(FeedPage page) {
        return Boolean.FALSE.equals(page.cacheMetadata().get("cacheHit")) ? 1 : 0;
    }

    private record CacheCounts(int hits, int misses) {
    }
}
