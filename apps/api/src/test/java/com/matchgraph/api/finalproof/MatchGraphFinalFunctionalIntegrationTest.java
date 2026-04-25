package com.matchgraph.api.finalproof;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.causal.CausalEvaluationRequest;
import com.matchgraph.api.causal.CausalEvaluationService;
import com.matchgraph.api.causal.PropensityBackfillRequest;
import com.matchgraph.api.causal.PropensityLoggingService;
import com.matchgraph.api.feed.DiscoveryFeedService;
import com.matchgraph.api.feed.FeedRefreshRequest;
import com.matchgraph.api.feed.FeedSnapshot;
import com.matchgraph.api.featureparity.FeatureParityCheckRequest;
import com.matchgraph.api.featureparity.FeatureParityService;
import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.graph.GraphEdgeService;
import com.matchgraph.api.ltr.CreateLtrModelRequest;
import com.matchgraph.api.ltr.CreateLtrModelVersionRequest;
import com.matchgraph.api.ltr.LtrModelRegistryService;
import com.matchgraph.api.ltr.LtrTrainingRequest;
import com.matchgraph.api.ltr.LtrTrainingService;
import com.matchgraph.api.modelquality.CalibrationRequest;
import com.matchgraph.api.modelquality.DriftRequest;
import com.matchgraph.api.modelquality.ModelCalibrationService;
import com.matchgraph.api.modelquality.ModelDriftService;
import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileResponse;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.profile.UpdateProfileLocationRequest;
import com.matchgraph.api.ranking.RankingService;
import com.matchgraph.api.realtime.CandidateInvalidationService;
import com.matchgraph.api.realtime.DeltaFeedRefreshService;
import com.matchgraph.api.realtime.NearlineFeatureMaterializerService;
import com.matchgraph.api.realtime.OnlineFeatureFreshnessGuardService;
import com.matchgraph.api.realtime.RealtimeFeedbackLoopDemoService;
import com.matchgraph.api.realtime.RealtimeInteractionService;
import com.matchgraph.api.realtime.RealtimeModels.CandidateInvalidationRequest;
import com.matchgraph.api.realtime.RealtimeModels.DeltaFeedRefreshRequest;
import com.matchgraph.api.realtime.RealtimeModels.FeatureFreshnessCheckRequest;
import com.matchgraph.api.realtime.RealtimeModels.NearlineFeatureMaterializationRequest;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionRequest;
import com.matchgraph.api.reward.LongTermRewardRequest;
import com.matchgraph.api.reward.LongTermRewardService;
import com.matchgraph.api.rolloutgate.ModelRolloutGateRequest;
import com.matchgraph.api.rolloutgate.ModelRolloutGateService;
import com.matchgraph.api.serving.MultiSurfaceRecommendationService;
import com.matchgraph.api.serving.RecommendationSurfaceService;
import com.matchgraph.api.serving.ServingModels.MultiStageServingRequest;
import com.matchgraph.api.serving.ServingModels.RecommendationSurfaceRequest;
import com.matchgraph.api.streaming.CacheInvalidationGraphService;
import com.matchgraph.api.streaming.CandidateTrendService;
import com.matchgraph.api.streaming.LiveQualityAnomalyService;
import com.matchgraph.api.streaming.OnlineModelKillSwitchService;
import com.matchgraph.api.streaming.RealtimeExperimentGuardrailService;
import com.matchgraph.api.streaming.RealtimeOperationsDemoService;
import com.matchgraph.api.streaming.SourceBackpressureService;
import com.matchgraph.api.streaming.SourceHealthService;
import com.matchgraph.api.streaming.StreamingFeatureWindowService;
import com.matchgraph.api.streaming.StreamingModels.StreamingFeatureWindowRequest;
import com.matchgraph.api.training.CreateTrainingDatasetRequest;
import com.matchgraph.api.training.TrainingDatasetService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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
class MatchGraphFinalFunctionalIntegrationTest {

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
        registry.add("matchgraph.clickhouse.url", MatchGraphFinalFunctionalIntegrationTest::clickhouseUrl);
    }

    @Autowired ProfileService profileService;
    @Autowired ApplicationContext applicationContext;
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired GraphEdgeService graphEdgeService;
    @Autowired DiscoveryFeedService feedService;
    @Autowired RankingService rankingService;
    @Autowired TrainingDatasetService trainingDatasetService;
    @Autowired FeatureParityService featureParityService;
    @Autowired LtrModelRegistryService modelRegistryService;
    @Autowired LtrTrainingService ltrTrainingService;
    @Autowired ModelCalibrationService calibrationService;
    @Autowired ModelDriftService driftService;
    @Autowired PropensityLoggingService propensityLoggingService;
    @Autowired CausalEvaluationService causalEvaluationService;
    @Autowired LongTermRewardService rewardService;
    @Autowired ModelRolloutGateService rolloutGateService;
    @Autowired RecommendationSurfaceService surfaceService;
    @Autowired MultiSurfaceRecommendationService multiStageService;
    @Autowired RealtimeFeedbackLoopDemoService realtimeDemoService;
    @Autowired RealtimeInteractionService realtimeInteractionService;
    @Autowired NearlineFeatureMaterializerService nearlineService;
    @Autowired CandidateInvalidationService invalidationService;
    @Autowired DeltaFeedRefreshService deltaFeedRefreshService;
    @Autowired OnlineFeatureFreshnessGuardService freshnessGuardService;
    @Autowired StreamingFeatureWindowService windowService;
    @Autowired CandidateTrendService trendService;
    @Autowired SourceHealthService sourceHealthService;
    @Autowired SourceBackpressureService backpressureService;
    @Autowired LiveQualityAnomalyService anomalyService;
    @Autowired RealtimeExperimentGuardrailService guardrailService;
    @Autowired OnlineModelKillSwitchService killSwitchService;
    @Autowired CacheInvalidationGraphService cacheGraphService;
    @Autowired RealtimeOperationsDemoService operationsDemoService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void finalFunctionalProofExercisesRecommendationRealtimeStreamingAndGuardrails() throws Exception {
        assertThat(applicationContext).isNotNull();
        for (int version = 1; version <= 14; version++) {
            assertThat(jdbcTemplate.queryForObject("select count(*) from flyway_schema_history where version = ? and success", Integer.class, String.valueOf(version))).isEqualTo(1);
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from pg_extension where extname = 'vector'", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from pg_extension where extname = 'postgis'", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(redis.isRunning()).isTrue();
        try (var redisConnection = redisConnectionFactory.getConnection()) {
            assertThat(redisConnection.ping()).isEqualTo("PONG");
        }
        assertThat(clickHouseAcceptsQueries()).isTrue();

        ProfileResponse actor = profile("final-actor");
        ProfileResponse safe = profile("final-safe");
        ProfileResponse negative = profile("final-negative");
        ProfileResponse blocked = profile("final-blocked");
        ProfileResponse reported = profile("final-reported");
        ProfileResponse hardExcluded = profile("final-hard-excluded");
        graphEdgeService.follow(actor.id(), new GraphActionRequest(safe.id(), "safe candidate"));
        graphEdgeService.follow(actor.id(), new GraphActionRequest(negative.id(), "negative candidate"));
        graphEdgeService.follow(actor.id(), new GraphActionRequest(blocked.id(), "blocked candidate"));
        graphEdgeService.follow(actor.id(), new GraphActionRequest(reported.id(), "reported candidate"));
        graphEdgeService.follow(actor.id(), new GraphActionRequest(hardExcluded.id(), "hard excluded candidate"));
        graphEdgeService.block(actor.id(), new GraphActionRequest(blocked.id(), "safety proof"));
        graphEdgeService.report(actor.id(), new GraphActionRequest(reported.id(), "safety proof"));
        graphEdgeService.mute(actor.id(), new GraphActionRequest(hardExcluded.id(), "hard exclusion proof"));
        realtimeInteractionService.ingest(new RealtimeInteractionRequest("final-report-" + UUID.randomUUID(), actor.id(), reported.id(), null, null, null, null, "REPORT", "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of()));
        invalidationService.create(new CandidateInvalidationRequest(actor.id(), hardExcluded.id(), null, "REPORTED", true, null, Map.of("finalProof", true, "hardExcluded", true)));
        recordInteraction(actor.id(), safe.id(), "LIKE");
        recordInteraction(actor.id(), negative.id(), "PASS");

        FeedSnapshot feed = feedService.refresh(actor.id(), new FeedRefreshRequest(null, 5));
        assertThat(feed.items()).noneMatch(item -> List.of(blocked.id(), reported.id(), hardExcluded.id()).contains(item.candidateProfileId()));
        recordInteraction(actor.id(), safe.id(), "LIKE");
        recordInteraction(actor.id(), negative.id(), "PASS");

        realtimeInteractionService.ingest(new RealtimeInteractionRequest("final-like-" + UUID.randomUUID(), actor.id(), safe.id(), feed.id(), null, null, null, "LIKE", "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of()));
        realtimeInteractionService.ingest(new RealtimeInteractionRequest("final-pass-" + UUID.randomUUID(), actor.id(), blocked.id(), feed.id(), null, null, null, "REPORT", "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of()));
        nearlineService.materialize(new NearlineFeatureMaterializationRequest(actor.id(), safe.id()));

        var dataset = trainingDatasetService.create(new CreateTrainingDatasetRequest("final-dataset-" + UUID.randomUUID(), OffsetDateTime.now().minusHours(2), OffsetDateTime.now().plusHours(1), 72, true, false, 100, Map.of()));
        modelRegistryService.createModel(new CreateLtrModelRequest("final-model", "Final Model"));
        modelRegistryService.createVersion("final-model", new CreateLtrModelVersionRequest("v1", "LOCAL_LINEAR", "schema-v1", dataset.id(), Map.of()));
        ltrTrainingService.train(new LtrTrainingRequest(dataset.id(), "final-model", "v1", "LOCAL_LINEAR_WEIGHTED", List.of("shared_interest_count", "source_count"), 0.5, 13L, Map.of(), true));
        var modelRanking = rankingService.run(actor.id(), feed.featureSnapshotRunId(), "ltr:final-model:v1", 5, "RANKING_RUN");
        assertThat(modelRanking.rankingContext()).containsEntry("modelBackedRanking", true);
        assertThat(modelRanking.rankingContext()).containsKeys("modelKey", "versionKey", "modelVersionId");
        assertThat(modelRanking.rankingContext()).containsEntry("modelKey", "final-model");
        assertThat(modelRanking.rankingContext()).containsEntry("versionKey", "v1");
        assertThat(modelRanking.items()).anySatisfy(item -> assertThat(item.reasons()).anyMatch(reason -> "MODEL_WEIGHTED_SCORE".equals(reason.reasonKey())));

        propensityLoggingService.backfill(new PropensityBackfillRequest(dataset.id()));
        assertThat(featureParityService.check(new FeatureParityCheckRequest(dataset.id(), null, Map.of(), List.of("shared_interest_count", "source_count"), 10)).summary()).containsKey("onlineSource");
        assertThat(calibrationService.calibrate(new CalibrationRequest("final-model", "v1", dataset.id(), 3)).summary()).containsEntry("metricSemantics", "approximate calibration over durable training examples");
        assertThat(driftService.detect(new DriftRequest(dataset.id(), dataset.id(), "v1", "v1", "final")).summary()).containsEntry("approximateMetrics", List.of("psiApprox"));
        assertThat(causalEvaluationService.evaluate(new CausalEvaluationRequest(dataset.id(), 5, true, 20.0)).result().propensityCoverage()).isNotNull();
        assertThat(rewardService.create(new LongTermRewardRequest(dataset.id(), null, 72, true, true)).result().labelledCount()).isGreaterThan(0);
        assertThat(rolloutGateService.create(new ModelRolloutGateRequest("final-model", "v1", null, null, Map.of())).checks()).isNotEmpty();

        surfaceService.create(new RecommendationSurfaceRequest("HOME_FEED", "ENABLED", "v1_balanced", List.of("GRAPH_MUTUALS", "GRAPH_TWO_HOP", "COLD_START"), 4, 250, Map.of(), Map.of(), Map.of("ruleRankingVersion", "v1_balanced"), Map.of("hardExclusions", "ALWAYS")));
        var served = multiStageService.multiStage(actor.id(), "HOME_FEED", new MultiStageServingRequest(null, 4, null, false, false, false));
        assertThat(served.servedItems()).noneMatch(item -> List.of(blocked.id(), reported.id(), hardExcluded.id()).contains(item.candidateProfileId()));
        assertThat(served.trace()).containsKeys("sourceRoutingPlanId", "preRankRunId", "heavyRankRunId", "slateOptimizationRunId", "servingQualityRunId");
        assertThat(jdbcTemplate.queryForObject("select count(*) from source_routing_plans where request_id = ?", Integer.class, served.requestId())).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from source_call_results where request_id = ?", Integer.class, served.requestId())).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from pre_rank_runs where request_id = ?", Integer.class, served.requestId())).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from heavy_rank_runs where request_id = ?", Integer.class, served.requestId())).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from slate_optimization_runs where request_id = ?", Integer.class, served.requestId())).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from serving_quality_runs where request_id = ?", Integer.class, served.requestId())).isGreaterThan(0);

        var pass = realtimeInteractionService.ingest(new RealtimeInteractionRequest("final-pass-safe-" + UUID.randomUUID(), actor.id(), safe.id(), feed.id(), null, served.requestId(), null, "PASS", "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of()));
        var duplicatePass = realtimeInteractionService.ingest(new RealtimeInteractionRequest(pass.event().eventKey(), actor.id(), safe.id(), feed.id(), null, served.requestId(), null, "PASS", "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of()));
        assertThat(duplicatePass.duplicate()).isTrue();
        assertThat(duplicatePass.event().id()).isEqualTo(pass.event().id());
        assertThat(invalidationService.invalidated(actor.id(), safe.id())).isTrue();
        var delta = deltaFeedRefreshService.refresh(actor.id(), feed.id(), new DeltaFeedRefreshRequest(pass.event().id(), served.requestId(), null, 2, "final proof pass"));
        assertThat(delta.removedCandidates()).contains(safe.id());
        assertThat(delta.newCandidates()).doesNotContain(safe.id(), blocked.id(), reported.id(), hardExcluded.id());
        var freshness = freshnessGuardService.check(new FeatureFreshnessCheckRequest(actor.id(), safe.id(), List.of("recent_affinity_score"), 1L, false, true));
        assertThat(freshness.results()).isNotEmpty();
        assertThat(freshness.summary()).containsEntry("modelBackedRankingGuard", "stale or missing required features require rebuild or fallback");
        assertThat(realtimeDemoService.run(actor.id(), safe.id(), null, feed.id()).steps()).hasSize(6);

        assertThat(windowService.materialize(new StreamingFeatureWindowRequest(actor.id(), safe.id(), "GRAPH_MUTUALS", "HOME_FEED")).summary()).containsEntry("approximate", true);
        assertThat(trendService.detect().scores()).isNotEmpty();
        assertThat(sourceHealthService.evaluate("GRAPH_MUTUALS").healthStatus()).isIn("HEALTHY", "DEGRADED", "BACKPRESSURED");
        assertThat(backpressureService.apply("GRAPH_MUTUALS", "REDUCE_BUDGET", 6, 2).budgetAfter()).isEqualTo(2);
        assertThat(sourceHealthService.budgetFor("GRAPH_MUTUALS", 6)).isEqualTo(2);
        assertThat(anomalyService.detect().summary()).containsEntry("approximate", true);
        var guardrail = guardrailService.pauseIfBad("final-experiment");
        assertThat(guardrail.decisions()).isNotEmpty();
        assertThat(guardrail.summary()).containsEntry("fallbackInstructionPersisted", true);
        assertThat(killSwitchService.kill("final-model", "v1", "final proof kill").status()).isEqualTo("KILLED");
        var killedFallback = multiStageService.multiStage(actor.id(), "HOME_FEED", new MultiStageServingRequest(null, 4, "ltr:final-model:v1", false, false, false));
        assertThat(killedFallback.warnings()).anyMatch(warning -> warning.contains("model fallback"));
        assertThat(killedFallback.trace().get("modelFallbackInfo").toString()).contains("fallbackReason");
        assertThat(jdbcTemplate.queryForObject("select count(*) from heavy_rank_runs where request_id = ? and fallback_used and fallback_reason is not null", Integer.class, killedFallback.requestId())).isGreaterThan(0);
        assertThat(cacheGraphService.invalidate("CANDIDATE", safe.id().toString(), false).actions()).isNotEmpty();
        var operationsDemo = operationsDemoService.run();
        assertThat(operationsDemo.steps()).hasSize(6);
        assertThat(operationsDemo.summary()).containsEntry("storedEvidence", true);

        assertThat(jdbcTemplate.queryForObject("select count(*) from realtime_recovery_traces", Integer.class)).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from multi_stage_serving_trace_steps", Integer.class)).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from realtime_feedback_loop_trace_steps", Integer.class)).isGreaterThan(0);
    }

    private ProfileResponse profile(String key) {
        ProfileResponse profile = profileService.create(new CreateProfileRequest(key, key, "USER", "ACTIVE", "bio", "London", "London", "UK"));
        profileService.updateLocation(profile.id(), new UpdateProfileLocationRequest(BigDecimal.valueOf(51.5), BigDecimal.valueOf(-0.1), BigDecimal.ONE, "London", "London", "UK"));
        return profile;
    }

    private void recordInteraction(UUID actorId, UUID candidateId, String type) {
        jdbcTemplate.update(
            """
                insert into interaction_events (
                    id, client_event_id, actor_profile_id, target_profile_id, event_type, occurred_at
                )
                values (?, ?, ?, ?, ?, now())
                """,
            UUID.randomUUID(),
            "final-" + type + "-" + UUID.randomUUID(),
            actorId,
            candidateId,
            type
        );
    }

    private static String clickhouseUrl() {
        return "jdbc:clickhouse://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123) + "/matchgraph?user=matchgraph&password=matchgraph";
    }

    private static boolean clickHouseAcceptsQueries() throws InterruptedException {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            try (var connection = DriverManager.getConnection(clickhouseUrl());
                 var statement = connection.createStatement();
                 var resultSet = statement.executeQuery("select 1")) {
                return resultSet.next();
            } catch (Exception ex) {
                lastFailure = ex;
                Thread.sleep(500);
            }
        }
        throw new AssertionError("ClickHouse container did not accept a proof query", lastFailure);
    }
}
