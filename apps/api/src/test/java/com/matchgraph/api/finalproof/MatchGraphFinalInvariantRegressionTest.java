package com.matchgraph.api.finalproof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.feed.DiscoveryFeedService;
import com.matchgraph.api.feed.FeedRefreshRequest;
import com.matchgraph.api.feed.FeedSnapshot;
import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.graph.GraphEdgeService;
import com.matchgraph.api.ltr.CreateLtrModelRequest;
import com.matchgraph.api.ltr.CreateLtrModelVersionRequest;
import com.matchgraph.api.ltr.LtrModelRegistryService;
import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileResponse;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.profile.UpdateProfileLocationRequest;
import com.matchgraph.api.ranking.RankingService;
import com.matchgraph.api.realtime.CandidateInvalidationService;
import com.matchgraph.api.realtime.DeltaFeedRefreshService;
import com.matchgraph.api.realtime.NearlineFeatureMaterializerService;
import com.matchgraph.api.realtime.OnlineFeatureFreshnessGuardService;
import com.matchgraph.api.realtime.RealtimeInteractionService;
import com.matchgraph.api.realtime.RealtimeModels.CandidateInvalidationRequest;
import com.matchgraph.api.realtime.RealtimeModels.DeltaFeedRefreshRequest;
import com.matchgraph.api.realtime.RealtimeModels.FeatureFreshnessCheckRequest;
import com.matchgraph.api.realtime.RealtimeModels.NearlineFeatureMaterializationRequest;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionRequest;
import com.matchgraph.api.rolloutgate.ModelRolloutGateRequest;
import com.matchgraph.api.rolloutgate.ModelRolloutGateService;
import com.matchgraph.api.serving.HeavyRankService;
import com.matchgraph.api.serving.MultiSurfaceRecommendationService;
import com.matchgraph.api.serving.PreRankService;
import com.matchgraph.api.serving.RecommendationSurfaceService;
import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.serving.ServingModels.MultiStageServingRequest;
import com.matchgraph.api.serving.ServingModels.RecommendationSurfaceRequest;
import com.matchgraph.api.serving.ServingModels.SourceCallResult;
import com.matchgraph.api.serving.SlateOptimizerService;
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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class MatchGraphFinalInvariantRegressionTest {

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired ProfileService profileService;
    @Autowired GraphEdgeService graphEdgeService;
    @Autowired DiscoveryFeedService feedService;
    @Autowired RecommendationSurfaceService surfaceService;
    @Autowired MultiSurfaceRecommendationService multiStageService;
    @Autowired PreRankService preRankService;
    @Autowired HeavyRankService heavyRankService;
    @Autowired SlateOptimizerService slateOptimizerService;
    @Autowired RealtimeInteractionService realtimeInteractionService;
    @Autowired CandidateInvalidationService invalidationService;
    @Autowired DeltaFeedRefreshService deltaFeedRefreshService;
    @Autowired NearlineFeatureMaterializerService nearlineService;
    @Autowired OnlineFeatureFreshnessGuardService freshnessGuardService;
    @Autowired StreamingFeatureWindowService windowService;
    @Autowired CandidateTrendService trendService;
    @Autowired SourceHealthService sourceHealthService;
    @Autowired SourceBackpressureService backpressureService;
    @Autowired LiveQualityAnomalyService anomalyService;
    @Autowired RealtimeExperimentGuardrailService guardrailService;
    @Autowired CacheInvalidationGraphService cacheGraphService;
    @Autowired OnlineModelKillSwitchService killSwitchService;
    @Autowired LtrModelRegistryService modelRegistryService;
    @Autowired ModelRolloutGateService rolloutGateService;
    @Autowired RankingService rankingService;
    @Autowired RealtimeOperationsDemoService operationsDemoService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void safetyGuardsPreventServingTrendBoostDeltaSlateAndCacheResurface() {
        ProfileResponse actor = profile("safety-actor");
        ProfileResponse safe = profile("safety-safe");
        ProfileResponse hardExcluded = profile("safety-hard");
        ProfileResponse reported = profile("safety-reported");
        ProfileResponse blocked = profile("safety-blocked");
        followAll(actor, safe, hardExcluded, reported, blocked);

        FeedSnapshot beforeInvalidation = feedService.refresh(actor.id(), new FeedRefreshRequest(null, 10));
        graphEdgeService.block(actor.id(), new GraphActionRequest(blocked.id(), "blocked invariant"));
        invalidationService.create(new CandidateInvalidationRequest(actor.id(), hardExcluded.id(), null, "REPORTED", true, null, Map.of("invariant", true, "hardExcluded", true)));
        invalidationService.create(new CandidateInvalidationRequest(actor.id(), blocked.id(), null, "BLOCKED", true, null, Map.of("invariant", true)));
        realtimeInteractionService.ingest(event("report-" + UUID.randomUUID(), actor.id(), reported.id(), "REPORT"));

        var source = new SourceCallResult("GRAPH_MUTUALS", 10, 4, false, false, false, null, null, List.of(
            item(safe.id(), "GRAPH_MUTUALS", "safe"),
            item(hardExcluded.id(), "GRAPH_MUTUALS", "hard"),
            item(reported.id(), "GRAPH_MUTUALS", "reported"),
            item(blocked.id(), "GRAPH_MUTUALS", "blocked")
        ));
        var preRank = preRankService.preRank(UUID.randomUUID(), actor.id(), List.of(source), null, 10);
        assertThat(preRank.survivors()).extracting(CandidateItem::candidateProfileId).contains(safe.id());
        assertThat(preRank.survivors()).noneMatch(candidate -> List.of(hardExcluded.id(), reported.id(), blocked.id()).contains(candidate.candidateProfileId()));

        windowService.materialize(new StreamingFeatureWindowRequest(actor.id(), reported.id(), "GRAPH_MUTUALS", "HOME_FEED"));
        var trends = trendService.detect();
        assertThat(trends.scores()).anySatisfy(score -> {
            assertThat(score.candidateProfileId()).isEqualTo(reported.id());
            assertThat(score.boostAllowed()).isFalse();
            assertThat(score.boundedBoost()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(score.boostBlockedReason()).contains("safety");
        });

        var slate = slateOptimizerService.optimize(UUID.randomUUID(), List.of(
            item(safe.id(), "GRAPH_MUTUALS", "safe"),
            new CandidateItem(hardExcluded.id(), "GRAPH_MUTUALS", BigDecimal.TEN, List.of("hard excluded"), true, "HARD_EXCLUDED"),
            new CandidateItem(reported.id(), "GRAPH_MUTUALS", BigDecimal.TEN, List.of("reported"), true, "REPORTED")
        ), 3, false);
        assertThat(slate.selected()).noneMatch(item -> List.of(hardExcluded.id(), reported.id()).contains(item.candidateProfileId()));
        assertThat(slate.dropped()).extracting(CandidateItem::candidateProfileId).contains(hardExcluded.id(), reported.id());

        homeSurface("v1_balanced");
        var delta = deltaFeedRefreshService.refresh(actor.id(), beforeInvalidation.id(), new DeltaFeedRefreshRequest(null, null, null, 4, "safety invariant"));
        assertThat(delta.newCandidates()).doesNotContain(hardExcluded.id(), reported.id(), blocked.id());
        var served = multiStageService.multiStage(actor.id(), "HOME_FEED", new MultiStageServingRequest(null, 6, null, false, false, false));
        assertThat(served.servedItems()).noneMatch(item -> List.of(hardExcluded.id(), reported.id(), blocked.id()).contains(item.candidateProfileId()));

        var cacheRun = cacheGraphService.invalidate("CANDIDATE", reported.id().toString(), false);
        assertThat(cacheRun.globalInvalidation()).isFalse();
        assertThat(cacheRun.actions()).allMatch(action -> !"GLOBAL_CLEAR".equals(action.actionType()));
        assertThat(cacheRun.actions()).allMatch(action -> Boolean.TRUE.equals(action.reason().get("targeted")));
    }

    @Test
    void duplicateRealtimeEventsDoNotDoubleCountNearlineStreamingOrInvalidationState() {
        ProfileResponse actor = profile("idempotent-actor");
        ProfileResponse candidate = profile("idempotent-candidate");
        String likeKey = "idempotent-like-" + UUID.randomUUID();

        var first = realtimeInteractionService.ingest(event(likeKey, actor.id(), candidate.id(), "LIKE"));
        var duplicate = realtimeInteractionService.ingest(event(likeKey, actor.id(), candidate.id(), "LIKE"));
        nearlineService.materialize(new NearlineFeatureMaterializationRequest(actor.id(), candidate.id()));
        windowService.materialize(new StreamingFeatureWindowRequest(actor.id(), candidate.id(), "GRAPH_MUTUALS", "HOME_FEED"));

        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.event().id()).isEqualTo(first.event().id());
        assertThat(jdbcTemplate.queryForObject("select count(*) from realtime_interaction_events where event_key = ?", Integer.class, likeKey)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select numeric_value from nearline_pair_features where profile_id = ? and candidate_profile_id = ? and feature_key = 'recent_affinity_score'", BigDecimal.class, actor.id(), candidate.id())).isEqualByComparingTo("1.0");
        assertThat(windowService.candidateWindows(candidate.id()).stream().filter(window -> "1h".equals(window.windowKey())).findFirst().orElseThrow().likes()).isEqualTo(1);

        String blockKey = "idempotent-block-" + UUID.randomUUID();
        var block = realtimeInteractionService.ingest(event(blockKey, actor.id(), candidate.id(), "BLOCK"));
        var duplicateBlock = realtimeInteractionService.ingest(event(blockKey, actor.id(), candidate.id(), "BLOCK"));
        assertThat(duplicateBlock.duplicate()).isTrue();
        assertThat(duplicateBlock.event().id()).isEqualTo(block.event().id());
        assertThat(jdbcTemplate.queryForObject("select count(*) from realtime_candidate_invalidations where event_id = ?", Integer.class, block.event().id())).isEqualTo(1);
        assertThat(invalidationService.invalidated(actor.id(), candidate.id())).isTrue();
    }

    @Test
    void freshnessGuardLabelsMissingStaleRebuiltAndModelFallbackRequirements() {
        ProfileResponse actor = profile("freshness-actor");
        ProfileResponse candidate = profile("freshness-candidate");

        var missing = freshnessGuardService.check(new FeatureFreshnessCheckRequest(actor.id(), candidate.id(), List.of("recent_affinity_score"), 1L, false, true));
        assertThat(missing.status()).isEqualTo("DEGRADED");
        assertThat(missing.results()).anySatisfy(result -> {
            assertThat(result.status()).isEqualTo("MISSING");
            assertThat(result.fallbackUsed()).isTrue();
            assertThat(result.detail()).containsEntry("fallbackRequired", true);
        });

        nearlineService.materialize(new NearlineFeatureMaterializationRequest(actor.id(), candidate.id()));
        jdbcTemplate.update("update nearline_pair_features set last_materialized_at = now() - interval '1 hour' where profile_id = ? and candidate_profile_id = ?", actor.id(), candidate.id());
        var stale = freshnessGuardService.check(new FeatureFreshnessCheckRequest(actor.id(), candidate.id(), List.of("recent_affinity_score"), 1L, false, true));
        assertThat(stale.status()).isEqualTo("DEGRADED");
        assertThat(stale.summary()).containsEntry("modelBackedRankingGuard", "stale or missing required features require rebuild or fallback");
        assertThat(stale.results()).anySatisfy(result -> {
            assertThat(result.status()).isEqualTo("STALE");
            assertThat(result.fallbackUsed()).isTrue();
        });

        var rebuilt = freshnessGuardService.check(new FeatureFreshnessCheckRequest(actor.id(), candidate.id(), List.of("recent_affinity_score"), 1L, true, true));
        assertThat(rebuilt.status()).isEqualTo("REBUILT");
        assertThat(rebuilt.results()).anySatisfy(result -> assertThat(result.status()).isEqualTo("REBUILT"));
    }

    @Test
    void servingDegradedFallbackPartialAndTraceReasonsArePersisted() {
        ProfileResponse actor = profile("serving-actor");
        ProfileResponse first = profile("serving-first");
        ProfileResponse second = profile("serving-second");
        followAll(actor, first, second);
        homeSurface("ltr:missing-model:v1");

        var timeout = multiStageService.multiStage(actor.id(), "HOME_FEED", new MultiStageServingRequest(null, 4, null, true, false, false));
        assertThat(timeout.degraded()).isTrue();
        assertThat(timeout.warnings()).anyMatch(warning -> warning.contains("source timeout"));
        assertThat(timeout.trace()).containsKeys("sourceRoutingPlanId", "preRankRunId", "heavyRankRunId", "slateOptimizationRunId", "servingQualityRunId");
        assertThat(jdbcTemplate.queryForObject("select count(*) from source_call_results where request_id = ? and degraded_reason is not null", Integer.class, timeout.requestId())).isGreaterThan(0);

        var fallback = multiStageService.multiStage(actor.id(), "HOME_FEED", new MultiStageServingRequest(null, 4, "ltr:missing-model:v1", false, true, false));
        assertThat(fallback.degraded()).isTrue();
        assertThat(fallback.warnings()).anyMatch(warning -> warning.contains("model fallback"));
        assertThat(fallback.trace().get("modelFallbackInfo").toString()).contains("fallbackReason");
        assertThat(jdbcTemplate.queryForObject("select count(*) from heavy_rank_runs where request_id = ? and fallback_used and fallback_reason is not null", Integer.class, fallback.requestId())).isGreaterThan(0);

        var partial = multiStageService.multiStage(actor.id(), "HOME_FEED", new MultiStageServingRequest(null, 4, null, false, false, true));
        assertThat(partial.degraded()).isTrue();
        assertThat(partial.warnings()).anyMatch(warning -> warning.contains("partial"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from slate_optimization_runs where request_id = ? and partial_result and warning is not null", Integer.class, partial.requestId())).isGreaterThan(0);
    }

    @Test
    void killedModelsAndRolloutGateStatesCannotSilentlyActivateRanking() {
        String modelKey = "guard-model-" + UUID.randomUUID();
        modelRegistryService.createModel(new CreateLtrModelRequest(modelKey, "Guard Model"));
        modelRegistryService.createVersion(modelKey, new CreateLtrModelVersionRequest("v1", "LOCAL_LINEAR", "schema-v1", null, Map.of()));
        killSwitchService.kill(modelKey, "v1", "invariant kill");

        ProfileResponse actor = profile("model-actor");
        ProfileResponse candidate = profile("model-candidate");
        graphEdgeService.follow(actor.id(), new GraphActionRequest(candidate.id(), "snapshot candidate"));
        FeedSnapshot feed = feedService.refresh(actor.id(), new FeedRefreshRequest(null, 5));
        var heavy = heavyRankService.rank(UUID.randomUUID(), "ltr:" + modelKey + ":v1", List.of(item(candidate.id(), "GRAPH_MUTUALS", "candidate")), false, false);
        assertThat(heavy.fallbackUsed()).isTrue();
        assertThat(heavy.modelBacked()).isFalse();
        assertThat(heavy.fallbackReason()).contains("kill switch");
        assertThat(heavy.ranked()).allSatisfy(item -> assertThat(item.reasons()).doesNotContain("MODEL_WEIGHTED_SCORE"));

        assertThatThrownBy(() -> rankingService.run(actor.id(), feed.featureSnapshotRunId(), "ltr:" + modelKey + ":v1", 5, "RANKING_RUN"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("killed");

        var rollout = rolloutGateService.create(new ModelRolloutGateRequest(modelKey, "v1", null, null, Map.of()));
        assertThat(rollout.recommendation()).isEqualTo("REJECT");
        assertThat(rollout.checks()).anyMatch(check -> check.required() && "NOT_AVAILABLE".equals(check.status()));
        assertThatThrownBy(() -> rolloutGateService.approveIfSafe(modelKey, "v1"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not safe to approve");
        assertThat(modelRegistryService.getVersion(modelKey, "v1").status()).isEqualTo("DRAFT");

        var restored = killSwitchService.restore(modelKey, "v1", false);
        assertThat(restored.status()).isEqualTo("RESTORED");
        assertThat(restored.requireRolloutGateReapproval()).isTrue();
        assertThat(restored.detail()).containsEntry("requiresRolloutGateReapproval", true);
    }

    @Test
    void experimentGuardrailSourceBackpressureAndEvidenceAreVisible() {
        windowService.materialize(new StreamingFeatureWindowRequest(null, null, "GRAPH_MUTUALS", "HOME_FEED"));
        sourceHealthService.evaluate("GRAPH_MUTUALS");
        var reduced = backpressureService.apply("GRAPH_MUTUALS", "REDUCE_BUDGET", 8, 3);
        assertThat(reduced.budgetAfter()).isLessThan(reduced.budgetBefore());
        assertThat(sourceHealthService.budgetFor("GRAPH_MUTUALS", 8)).isEqualTo(3);

        var restored = backpressureService.restore("GRAPH_MUTUALS");
        assertThat(restored.action()).isEqualTo("RESTORE");
        assertThat(sourceHealthService.budgetFor("GRAPH_MUTUALS", 8)).isEqualTo(8);

        var anomalyRun = anomalyService.detect();
        assertThat(anomalyRun.summary()).containsEntry("approximate", true);
        assertThat(jdbcTemplate.queryForObject("select count(*) from live_quality_anomaly_runs where id = ?", Integer.class, anomalyRun.id())).isEqualTo(1);

        var guardrail = guardrailService.pauseIfBad("guardrail-exp-" + UUID.randomUUID());
        assertThat(guardrail.summary()).containsEntry("evidenceBased", true);
        assertThat(guardrail.summary()).containsEntry("fallbackInstructionPersisted", true);
        assertThat(guardrail.decisions()).allSatisfy(decision -> assertThat(decision.reason()).isNotEmpty());
        assertThat(guardrailService.decisions(guardrail.decisions().getFirst().experimentKey())).isNotEmpty();
    }

    @Test
    void cacheInvalidationIsTargetedAndUnsupportedTargetsAreHonest() {
        ProfileResponse profile = profile("cache-profile");
        cacheGraphService.build();
        var targeted = cacheGraphService.invalidate("PROFILE", profile.id().toString(), false);
        assertThat(targeted.globalInvalidation()).isFalse();
        assertThat(targeted.summary()).containsEntry("preciseByDefault", true);
        assertThat(targeted.actions()).allMatch(action -> Boolean.FALSE.equals(action.reason().get("globalClear")));

        var unsupported = cacheGraphService.invalidate("UNKNOWN_NODE", "unsupported-" + UUID.randomUUID(), false);
        assertThat(unsupported.actions()).anySatisfy(action -> {
            assertThat(action.executionStatus()).isEqualTo("NOT_SUPPORTED");
            assertThat(action.reason()).containsEntry("targeted", true);
        });

        var candidateRun = cacheGraphService.invalidate("CANDIDATE", UUID.randomUUID().toString(), false);
        assertThat(candidateRun.actions()).anyMatch(action -> "INVALIDATE_FEATURES".equals(action.actionType()));
        assertThat(cacheGraphService.affected("PROFILE", "unrelated-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void operationsDemoMarksSimulationAndPersistsSixRecoveryTracesIncludingRestoreEvidence() {
        var demo = operationsDemoService.run();
        assertThat(demo.steps()).hasSize(6);
        assertThat(demo.steps()).allSatisfy(step -> assertThat(detail(step)).containsEntry("simulation", true));
        assertThat(jdbcTemplate.queryForObject("select count(*) from realtime_operations_demo_steps where demo_run_id = ? and trace_id is not null", Integer.class, demo.demoRunId())).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("select count(*) from realtime_recovery_traces where scenario_key = 'RECOVERY_RESTORE'", Integer.class)).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("select summary_json::text from realtime_recovery_traces where scenario_key = 'RECOVERY_RESTORE' order by created_at desc limit 1", String.class)).contains("sourceAction", "modelStatus");
    }

    private ProfileResponse profile(String key) {
        String unique = key + "-" + UUID.randomUUID();
        ProfileResponse profile = profileService.create(new CreateProfileRequest(unique, unique, "USER", "ACTIVE", "bio", "London", "London", "UK"));
        profileService.updateLocation(profile.id(), new UpdateProfileLocationRequest(BigDecimal.valueOf(51.5), BigDecimal.valueOf(-0.1), BigDecimal.ONE, "London", "London", "UK"));
        return profile;
    }

    private void followAll(ProfileResponse actor, ProfileResponse... candidates) {
        for (ProfileResponse candidate : candidates) {
            graphEdgeService.follow(actor.id(), new GraphActionRequest(candidate.id(), "invariant candidate"));
        }
    }

    private CandidateItem item(UUID candidateId, String source, String reason) {
        return new CandidateItem(candidateId, source, BigDecimal.ONE, List.of(reason), false, null);
    }

    private RealtimeInteractionRequest event(String eventKey, UUID actor, UUID candidate, String eventType) {
        return new RealtimeInteractionRequest(eventKey, actor, candidate, null, null, null, null, eventType, "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of());
    }

    private void homeSurface(String rankingVersion) {
        surfaceService.create(new RecommendationSurfaceRequest(
            "HOME_FEED",
            "ENABLED",
            rankingVersion,
            List.of("GRAPH_MUTUALS", "GRAPH_TWO_HOP", "COLD_START"),
            6,
            250,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of("hardExclusions", "ALWAYS")
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail(Map<String, Object> step) {
        return (Map<String, Object>) step.get("detail");
    }
}
