package com.matchgraph.api.scale;

import java.util.List;
import java.util.UUID;

import com.matchgraph.api.features.FeatureSnapshotRun;
import com.matchgraph.api.features.FeatureSnapshotService;
import com.matchgraph.api.feed.DiscoveryFeedService;
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

    public ScaleBenchmarkService(
        ScaleRepository scaleRepository,
        CandidateRetrievalService retrievalService,
        FeatureSnapshotService featureSnapshotService,
        RankingService rankingService,
        DiscoveryFeedService feedService
    ) {
        this.scaleRepository = scaleRepository;
        this.retrievalService = retrievalService;
        this.featureSnapshotService = featureSnapshotService;
        this.rankingService = rankingService;
        this.feedService = feedService;
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
            scaleRepository.insertBenchmarkResult(run.id(), profileId, retrievalMs, snapshotMs, rankingMs, feedMs, retrieval.finalCandidateCount());
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
}
