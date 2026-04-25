package com.matchgraph.api.scale;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.matchgraph.api.embedding.CompleteEmbeddingRefreshBatchRequest;
import com.matchgraph.api.embedding.CreateEmbeddingRefreshBatchRequest;
import com.matchgraph.api.embedding.EmbeddingLifecycleService;
import com.matchgraph.api.embedding.EmbeddingRefreshBatch;
import com.matchgraph.api.embedding.EmbeddingRefreshRequest;
import com.matchgraph.api.embedding.EmbeddingRefreshRequestBody;
import com.matchgraph.api.evaluation.CounterfactualEvaluationResponse;
import com.matchgraph.api.evaluation.CounterfactualEvaluationRequest;
import com.matchgraph.api.evaluation.CounterfactualEvaluationService;
import com.matchgraph.api.evaluation.OfflineEvaluationRequest;
import com.matchgraph.api.evaluation.OfflineEvaluationResponse;
import com.matchgraph.api.evaluation.OfflineEvaluationService;
import com.matchgraph.api.experiment.ExperimentService;
import com.matchgraph.api.experiment.RankingExperimentAssignment;
import com.matchgraph.api.experiment.RankingExperimentCreateRequest;
import com.matchgraph.api.experiment.RankingExperimentVariantRequest;
import com.matchgraph.api.features.CandidateFeatureSnapshot;
import com.matchgraph.api.features.CandidateFeatureValue;
import com.matchgraph.api.features.FeatureSnapshotRun;
import com.matchgraph.api.features.FeatureSnapshotService;
import com.matchgraph.api.feed.DiscoveryFeedService;
import com.matchgraph.api.feed.FeedItem;
import com.matchgraph.api.feed.FeedPage;
import com.matchgraph.api.feed.FeedRefreshRequest;
import com.matchgraph.api.feed.FeedSnapshot;
import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.graph.GraphEdgeService;
import com.matchgraph.api.interaction.InteractionService;
import com.matchgraph.api.interaction.RecordInteractionRequest;
import com.matchgraph.api.metrics.RankingMetricsIngestResponse;
import com.matchgraph.api.metrics.RankingMetricsService;
import com.matchgraph.api.metrics.RankingMetricsSummaryResponse;
import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileEmbeddingStatusResponse;
import com.matchgraph.api.profile.ProfileInterestRequest;
import com.matchgraph.api.profile.ProfileResponse;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.profile.UpdateProfileInterestsRequest;
import com.matchgraph.api.profile.UpdateProfileLocationRequest;
import com.matchgraph.api.profile.UpdateProfileRequest;
import com.matchgraph.api.profile.UpsertProfileEmbeddingRequest;
import com.matchgraph.api.ranking.RankingDecision;
import com.matchgraph.api.ranking.RankingService;
import com.matchgraph.api.retrieval.CandidateRetrievalRun;
import com.matchgraph.api.retrieval.CandidateRetrievalService;
import com.matchgraph.api.retrieval.CandidateSourceType;
import com.matchgraph.api.retrieval.RunRetrievalRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class Mgrp016To022ExperimentsMetricsEvaluationCacheScaleIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE =
        DockerImageName.parse("garapadev/postgres-postgis-pgvector:16-optimized")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
        .withDatabaseName("matchgraph")
        .withUsername("matchgraph")
        .withPassword("matchgraph");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
        .withExposedPorts(6379);

    @Container
    static final GenericContainer<?> clickhouse = new GenericContainer<>(DockerImageName.parse("clickhouse/clickhouse-server:25.3-alpine"))
        .withEnv("CLICKHOUSE_DB", "matchgraph")
        .withEnv("CLICKHOUSE_USER", "matchgraph")
        .withEnv("CLICKHOUSE_PASSWORD", "matchgraph")
        .withExposedPorts(8123);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("matchgraph.clickhouse.url", () -> "jdbc:clickhouse://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123) + "/matchgraph?user=matchgraph&password=matchgraph");
    }

    @Autowired
    ProfileService profileService;
    @Autowired
    GraphEdgeService graphEdgeService;
    @Autowired
    CandidateRetrievalService retrievalService;
    @Autowired
    FeatureSnapshotService featureSnapshotService;
    @Autowired
    ExperimentService experimentService;
    @Autowired
    DiscoveryFeedService feedService;
    @Autowired
    RankingService rankingService;
    @Autowired
    InteractionService interactionService;
    @Autowired
    RankingMetricsService metricsService;
    @Autowired
    OfflineEvaluationService offlineEvaluationService;
    @Autowired
    CounterfactualEvaluationService counterfactualEvaluationService;
    @Autowired
    EmbeddingLifecycleService embeddingLifecycleService;
    @Autowired
    ScaleSeedService scaleSeedService;
    @Autowired
    ScaleBenchmarkService scaleBenchmarkService;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ApplicationContext applicationContext;

    @Test
    void provesMgrp016To022MegaSliceThroughRuntimePath() {
        ProfileResponse actor = seededProfile("mgrp016-actor", "Actor", "Glasgow", 0.001);
        ProfileResponse graphCandidate = seededProfile("mgrp016-graph", "Graph Candidate", "Glasgow", 0.0015);
        ProfileResponse vectorCandidate = seededProfile("mgrp016-vector", "Vector Candidate", "Glasgow", 0.0020);
        ProfileResponse otherCandidate = seededProfile("mgrp016-other", "Other Candidate", "Edinburgh", 0.0030);
        ProfileResponse connector = seededProfile("mgrp016-connector", "Connector", "Glasgow", 0.0040);

        graphEdgeService.follow(actor.id(), new GraphActionRequest(connector.id(), "test graph connector"));
        graphEdgeService.follow(connector.id(), new GraphActionRequest(graphCandidate.id(), "test graph two-hop"));
        graphEdgeService.follow(actor.id(), new GraphActionRequest(vectorCandidate.id(), "test direct graph"));

        CandidateRetrievalRun retrieval = retrievalService.run(actor.id(), new RunRetrievalRequest(20, null, null));
        assertThat(retrieval.candidates()).isNotEmpty();
        assertThat(retrieval.candidates()).anySatisfy(candidate -> assertThat(candidate.sourceTypes()).contains(CandidateSourceType.VECTOR_SIMILARITY));

        FeatureSnapshotRun snapshots = featureSnapshotService.createFromRetrieval(actor.id(), retrieval.id());
        assertThat(snapshots.candidates()).isNotEmpty();

        experimentService.create(new RankingExperimentCreateRequest(
            "mgrp016-runtime",
            "MGRP016 runtime proof",
            "ACTIVE",
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(10),
            Map.of("guardrail", "integration-proof"),
            List.of(
                new RankingExperimentVariantRequest("graph", "v1_graph_affinity", BigDecimal.valueOf(45), Map.of()),
                new RankingExperimentVariantRequest("vector", "v1_vector_affinity", BigDecimal.valueOf(45), Map.of())
            )
        ));

        RankingExperimentAssignment assignment = experimentService.assign(actor.id(), "mgrp016-runtime");
        RankingExperimentAssignment persisted = experimentService.assign(actor.id(), "mgrp016-runtime");
        assertThat(persisted.id()).isEqualTo(assignment.id());
        assertThat(persisted.assignmentHash()).isEqualTo(assignment.assignmentHash());

        FeedSnapshot feed = feedService.refresh(actor.id(), new FeedRefreshRequest(retrieval.id(), 5, "mgrp016-runtime"));
        RankingDecision feedDecision = rankingService.get(actor.id(), feed.rankingDecisionLogId());
        assertThat(feedDecision.rankingVersion()).isEqualTo(assignment.assignedRankingVersion());
        assertThat(feedDecision.rankingContext())
            .containsEntry("experimentKey", "mgrp016-runtime")
            .containsEntry("assignedVariant", assignment.assignedVariantKey())
            .containsEntry("assignmentId", assignment.id().toString())
            .containsEntry("requestedLimit", 5);
        assertThat((List<?>) feedDecision.rankingContext().get("recentlySeenCandidateIds")).isNotNull();

        FeedPage firstRead = feedService.read(actor.id(), 3, null);
        FeedPage secondRead = feedService.read(actor.id(), 3, null);
        assertThat(firstRead.cacheMetadata()).containsEntry("cacheHit", false);
        assertThat(secondRead.cacheMetadata()).containsEntry("cacheHit", true);

        FeedItem top = feed.items().getFirst();
        interactionService.record(actor.id(), interaction(top, "PROFILE_VIEW", "mgrp016-view", feed, feedDecision));
        interactionService.record(actor.id(), interaction(top, "LIKE", "mgrp016-like", feed, feedDecision));
        interactionService.record(actor.id(), new RecordInteractionRequest(
            "mgrp016-unattributed",
            otherCandidate.id(),
            "PASS",
            OffsetDateTime.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of()
        ));

        RankingMetricsIngestResponse firstIngest = metricsService.ingest();
        RankingMetricsSummaryResponse firstSummary = metricsService.summary();
        RankingMetricsIngestResponse secondIngest = metricsService.ingest();
        RankingMetricsSummaryResponse secondSummary = metricsService.summary();
        assertThat(firstIngest.servedRows()).isGreaterThan(0);
        assertThat(firstIngest.interactionRows()).isGreaterThan(0);
        assertThat(firstIngest.skippedInteractionRows()).isGreaterThan(0);
        assertThat(secondIngest.totalRows()).isEqualTo(firstIngest.totalRows());
        assertThat(secondSummary.rows()).usingRecursiveComparison().isEqualTo(firstSummary.rows());
        assertThat(firstSummary.rows()).anySatisfy(row -> {
            assertThat(row.rankingVersion()).isEqualTo(feedDecision.rankingVersion());
            assertThat(row.candidateSource()).isNotBlank();
            assertThat(row.positionBucket()).isNotBlank();
        });
        assertThat(firstSummary.rows()).anySatisfy(row -> assertThat(row.eventType()).isEqualTo("LIKE"));

        OfflineEvaluationResponse offline = offlineEvaluationService.evaluate(new OfflineEvaluationRequest(feedDecision.rankingVersion(), null, null, 5, "mgrp016-runtime"));
        assertThat(offline.result().precisionAtK()).isNotNull();
        assertThat(offline.result().mrr()).isNotNull();
        assertThat(offline.result().ndcgAtK()).isNotNull();
        assertThat(offline.result().coverage()).isNotNull();
        assertThat(offline.result().diversity()).isNotNull();
        assertThat(offline.result().negativeSignalPenalty()).isNotNull();
        assertThat(offline.result().staleEmbeddingCount()).isGreaterThanOrEqualTo(0);
        assertThat(offline.result().result()).containsKeys("labelSemantics", "denominators", "coverageSemantics", "diversitySemantics");

        int retrievalRunsBefore = count("candidate_retrieval_runs");
        int snapshotRunsBefore = count("feature_snapshot_runs");
        int feedSnapshotsBefore = count("feed_snapshots");
        CounterfactualEvaluationResponse counterfactual = counterfactualEvaluationService.evaluate(
            new CounterfactualEvaluationRequest(feedDecision.id(), alternate(feedDecision.rankingVersion()), 5)
        );
        assertThat(count("candidate_retrieval_runs")).isEqualTo(retrievalRunsBefore);
        assertThat(count("feature_snapshot_runs")).isEqualTo(snapshotRunsBefore);
        assertThat(count("feed_snapshots")).isEqualTo(feedSnapshotsBefore);
        assertThat(counterfactual.items()).isNotEmpty();
        assertThat(counterfactual.items()).anySatisfy(item -> assertThat(item.topKChange()).isNotBlank());

        ProfileResponse staleCandidate = profileService.update(
            top.candidateProfileId(),
            new UpdateProfileRequest("Refreshed Candidate", null, "semantic change", null, null, null, null)
        );
        assertThat(staleCandidate.embeddingStatus()).isEqualTo("STALE");
        CandidateRetrievalRun staleRetrieval = retrievalService.run(actor.id(), new RunRetrievalRequest(20, null, null));
        assertThat(staleRetrieval.candidates().stream()
            .filter(candidate -> candidate.candidateProfileId().equals(top.candidateProfileId()))
            .flatMap(candidate -> candidate.sourceTypes().stream()))
            .doesNotContain(CandidateSourceType.VECTOR_SIMILARITY);

        ProfileEmbeddingStatusResponse beforeRefresh = profileService.embeddingStatus(top.candidateProfileId());
        EmbeddingRefreshRequest refreshRequest = embeddingLifecycleService.request(
            top.candidateProfileId(),
            new EmbeddingRefreshRequestBody("integration stale refresh", "mgrp016-test")
        );
        assertThat(refreshRequest.currentEmbeddingStatus()).isEqualTo("STALE");
        EmbeddingRefreshBatch batch = embeddingLifecycleService.createBatch(new CreateEmbeddingRefreshBatchRequest(10, "integration"));
        assertThat(batch.items()).anySatisfy(item -> assertThat(item.profileId()).isEqualTo(top.candidateProfileId()));
        EmbeddingRefreshBatch completed = embeddingLifecycleService.completeBatch(
            batch.id(),
            new CompleteEmbeddingRefreshBatchRequest(List.of(
                new CompleteEmbeddingRefreshBatchRequest.CompletedEmbeddingRefreshItem(
                    top.candidateProfileId(),
                    "mgrp016-refreshed-v2",
                    "test-model",
                    vector(0.009)
                )
            ))
        );
        assertThat(completed.status()).isEqualTo("COMPLETED");
        ProfileEmbeddingStatusResponse afterRefresh = profileService.embeddingStatus(top.candidateProfileId());
        assertThat(afterRefresh.embeddingStatus()).isEqualTo("CURRENT");
        assertThat(afterRefresh.activeVersionName()).isNotEqualTo(beforeRefresh.activeVersionName());
        assertThat(activeEmbeddingCount(top.candidateProfileId())).isEqualTo(1);
        CandidateRetrievalRun refreshedRetrieval = retrievalService.run(actor.id(), new RunRetrievalRequest(20, null, null));
        assertThat(refreshedRetrieval.candidates().stream()
            .filter(candidate -> candidate.candidateProfileId().equals(top.candidateProfileId()))
            .flatMap(candidate -> candidate.sourceTypes().stream()))
            .contains(CandidateSourceType.VECTOR_SIMILARITY);
        FeatureSnapshotRun refreshedSnapshots = featureSnapshotService.createFromRetrieval(actor.id(), refreshedRetrieval.id());
        CandidateFeatureSnapshot refreshedSnapshot = snapshot(refreshedSnapshots, top.candidateProfileId());
        assertThat(value(refreshedSnapshot, "embedding_version").textValue()).isEqualTo("mgrp016-refreshed-v2");
        assertThat(value(refreshedSnapshot, "embedding_version").freshnessStatus()).isEqualTo("FRESH");

        feedService.read(actor.id(), 5, null);
        graphEdgeService.block(actor.id(), new GraphActionRequest(top.candidateProfileId(), "cache invalidation proof"));
        FeedPage afterBlock = feedService.read(actor.id(), 5, null);
        assertThat(afterBlock.items()).extracting(FeedItem::candidateProfileId).doesNotContain(top.candidateProfileId());

        ScaleSeedRun seed = scaleSeedService.seed(new ScaleSeedRequest(12, 18, 16, true, true, 3, 20260425L, false));
        assertThat(seed.randomSeed()).isEqualTo(20260425L);
        assertThat(seed.summary())
            .containsEntry("createdProfiles", 12)
            .containsEntry("createdInterests", 12)
            .containsEntry("createdLocations", 12)
            .containsEntry("createdEmbeddings", 12)
            .containsEntry("createdEdgesRequested", 18)
            .containsEntry("createdInteractionsRequested", 16)
            .containsEntry("staleEmbeddingProfiles", 2)
            .containsEntry("safetyStateProfiles", 12);
        assertThat(count("profile_graph_edges")).isGreaterThanOrEqualTo(12);
        assertThat(count("interaction_events")).isGreaterThanOrEqualTo(19);

        RankingBenchmarkResponse benchmark = scaleBenchmarkService.benchmark(new RankingBenchmarkRequest(seed.id(), 2, true, true));
        assertThat(benchmark.results()).hasSize(2);
        assertThat(benchmark.results()).allSatisfy(result -> {
            assertThat(result.retrievalLatencyMs()).isGreaterThanOrEqualTo(0);
            assertThat(result.snapshotLatencyMs()).isGreaterThanOrEqualTo(0);
            assertThat(result.rankingLatencyMs()).isGreaterThanOrEqualTo(0);
            assertThat(result.feedLatencyMs()).isGreaterThanOrEqualTo(0);
            assertThat(result.candidateCount()).isGreaterThanOrEqualTo(0);
            assertThat(result.result()).containsEntry("benchmarkType", "deterministic local ranking benchmark");
        });

        assertThat(Arrays.stream(applicationContext.getBeanDefinitionNames()))
            .noneMatch(name -> name.toLowerCase().contains("shadow")
                || name.toLowerCase().contains("champion")
                || name.toLowerCase().contains("bandit")
                || name.toLowerCase().contains("interleav")
                || name.toLowerCase().contains("fairness")
                || name.toLowerCase().contains("groundtruth"));
    }

    private ProfileResponse seededProfile(String externalRef, String displayName, String city, double embeddingSeed) {
        ProfileResponse profile = profileService.create(new CreateProfileRequest(
            externalRef,
            displayName,
            "USER",
            "ACTIVE",
            "Integration proof profile",
            city,
            "Scotland",
            "GB"
        ));
        profileService.updateInterests(profile.id(), new UpdateProfileInterestsRequest(List.of(
            new ProfileInterestRequest("topic", "ranking", BigDecimal.ONE),
            new ProfileInterestRequest("topic", city.toLowerCase(), BigDecimal.valueOf(0.5))
        )));
        profileService.updateLocation(profile.id(), new UpdateProfileLocationRequest(
            "Glasgow".equals(city) ? BigDecimal.valueOf(55.8642) : BigDecimal.valueOf(55.9533),
            "Glasgow".equals(city) ? BigDecimal.valueOf(-4.2518) : BigDecimal.valueOf(-3.1883),
            BigDecimal.valueOf(20),
            city,
            "Scotland",
            "GB"
        ));
        profileService.upsertEmbedding(profile.id(), new UpsertProfileEmbeddingRequest("mgrp016-v1", "test-model", vector(embeddingSeed)));
        return profileService.get(profile.id());
    }

    private RecordInteractionRequest interaction(FeedItem item, String eventType, String clientEventId, FeedSnapshot feed, RankingDecision decision) {
        return new RecordInteractionRequest(
            clientEventId,
            item.candidateProfileId(),
            eventType,
            OffsetDateTime.now(),
            "mgrp016-request",
            feed.retrievalRunId(),
            item.sourceTypes().getFirst(),
            decision.rankingVersion(),
            String.valueOf(decision.rankingContext().get("assignmentId")),
            (String) decision.rankingContext().get("assignedVariant"),
            item.position(),
            Map.of("decisionLogId", decision.id().toString())
        );
    }

    private List<Double> vector(double base) {
        return IntStream.range(0, 384)
            .mapToObj(index -> base + (index / 10_000.0d))
            .toList();
    }

    private String alternate(String rankingVersion) {
        return "v1_graph_affinity".equals(rankingVersion) ? "v1_vector_affinity" : "v1_graph_affinity";
    }

    private CandidateFeatureSnapshot snapshot(FeatureSnapshotRun run, UUID candidateId) {
        return run.candidates().stream()
            .filter(candidate -> candidate.candidateProfileId().equals(candidateId))
            .findFirst()
            .orElseThrow();
    }

    private CandidateFeatureValue value(CandidateFeatureSnapshot snapshot, String key) {
        return snapshot.values().stream()
            .filter(value -> value.featureKey().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int activeEmbeddingCount(UUID profileId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from profile_embeddings where profile_id = ? and is_active",
            Integer.class,
            profileId
        );
    }
}
