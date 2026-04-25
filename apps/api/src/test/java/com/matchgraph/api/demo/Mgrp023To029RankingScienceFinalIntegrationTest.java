package com.matchgraph.api.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.bandit.BanditArmRequest;
import com.matchgraph.api.bandit.BanditDecision;
import com.matchgraph.api.bandit.BanditDecisionRequest;
import com.matchgraph.api.bandit.BanditPolicyRequest;
import com.matchgraph.api.bandit.BanditPolicyService;
import com.matchgraph.api.bandit.BanditReward;
import com.matchgraph.api.bandit.BanditRewardRequest;
import com.matchgraph.api.bandit.BanditDecisionService;
import com.matchgraph.api.bandit.BanditRewardService;
import com.matchgraph.api.evaluation.CounterfactualEvaluationRequest;
import com.matchgraph.api.evaluation.CounterfactualEvaluationService;
import com.matchgraph.api.explainability.CandidateExplanation;
import com.matchgraph.api.explainability.RankingExplainabilityService;
import com.matchgraph.api.exposure.ExposureControlPolicy;
import com.matchgraph.api.exposure.ExposurePolicyRequest;
import com.matchgraph.api.exposure.ExposurePolicyService;
import com.matchgraph.api.features.FeatureSnapshotRun;
import com.matchgraph.api.features.FeatureSnapshotService;
import com.matchgraph.api.feed.DiscoveryFeedService;
import com.matchgraph.api.feed.FeedRefreshRequest;
import com.matchgraph.api.feed.FeedSnapshot;
import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.graph.GraphEdgeService;
import com.matchgraph.api.interleaving.InterleavingExperimentRequest;
import com.matchgraph.api.interleaving.InterleavingOutcome;
import com.matchgraph.api.interleaving.InterleavingOutcomeRequest;
import com.matchgraph.api.interleaving.InterleavingService;
import com.matchgraph.api.interleaving.InterleavingSession;
import com.matchgraph.api.interleaving.InterleavingSessionRequest;
import com.matchgraph.api.ranking.RankingDecision;
import com.matchgraph.api.ranking.RankingService;
import com.matchgraph.api.retrieval.CandidateRetrievalRun;
import com.matchgraph.api.retrieval.CandidateRetrievalService;
import com.matchgraph.api.retrieval.RunRetrievalRequest;
import com.matchgraph.api.shadow.ChampionChallengerConfigRequest;
import com.matchgraph.api.shadow.ChampionChallengerDecision;
import com.matchgraph.api.shadow.ChampionChallengerEvaluateRequest;
import com.matchgraph.api.shadow.ChampionChallengerService;
import com.matchgraph.api.shadow.ShadowRankingRun;
import com.matchgraph.api.shadow.ShadowRankingRunRequest;
import com.matchgraph.api.shadow.ShadowRankingService;
import com.matchgraph.api.synthetic.GroundTruthEvaluationService;
import com.matchgraph.api.synthetic.SyntheticEvaluationRequest;
import com.matchgraph.api.synthetic.SyntheticEvaluationRun;
import com.matchgraph.api.synthetic.SyntheticPopulationRequest;
import com.matchgraph.api.synthetic.SyntheticPopulationRun;
import com.matchgraph.api.synthetic.SyntheticPopulationService;

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
class Mgrp023To029RankingScienceFinalIntegrationTest {

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
    SyntheticPopulationService syntheticPopulationService;
    @Autowired
    CandidateRetrievalService retrievalService;
    @Autowired
    FeatureSnapshotService featureSnapshotService;
    @Autowired
    RankingService rankingService;
    @Autowired
    DiscoveryFeedService feedService;
    @Autowired
    ShadowRankingService shadowRankingService;
    @Autowired
    ChampionChallengerService championChallengerService;
    @Autowired
    RankingExplainabilityService explainabilityService;
    @Autowired
    BanditPolicyService banditPolicyService;
    @Autowired
    BanditDecisionService banditDecisionService;
    @Autowired
    BanditRewardService banditRewardService;
    @Autowired
    InterleavingService interleavingService;
    @Autowired
    ExposurePolicyService exposurePolicyService;
    @Autowired
    GroundTruthEvaluationService groundTruthEvaluationService;
    @Autowired
    RankingScienceDemoService demoService;
    @Autowired
    GraphEdgeService graphEdgeService;
    @Autowired
    CounterfactualEvaluationService counterfactualEvaluationService;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ApplicationContext applicationContext;

    @Test
    void provesMgrp023To029RankingScienceFinalLayer() {
        SyntheticPopulationRun population = syntheticPopulationService.create(new SyntheticPopulationRequest(23029L, 10, 3, BigDecimal.valueOf(0.40), Map.of("test", true)));
        assertThat(population.profiles()).hasSize(10);
        assertThat(count("synthetic_ground_truth_labels")).isGreaterThan(0);
        SyntheticPopulationRun repeatedPopulation = syntheticPopulationService.create(new SyntheticPopulationRequest(23029L, 10, 3, BigDecimal.valueOf(0.40), Map.of("test", "repeatability")));
        assertThat(repeatedPopulation.profiles()).hasSize(population.profiles().size());
        assertThat(repeatedPopulation.clusterCount()).isEqualTo(population.clusterCount());
        assertThat(labelCount(repeatedPopulation.id())).isEqualTo(labelCount(population.id()));

        UUID actor = population.profiles().getFirst().profileId();
        UUID hidden = population.profiles().getLast().profileId();
        CandidateRetrievalRun retrieval = retrievalService.run(actor, new RunRetrievalRequest(30, null, null));
        FeatureSnapshotRun snapshots = featureSnapshotService.createFromRetrieval(actor, retrieval.id());
        RankingDecision baseline = rankingService.run(actor, snapshots.id(), "v1_balanced", 10, "RANKING_RUN");
        FeedSnapshot feed = feedService.refresh(actor, new FeedRefreshRequest(retrieval.id(), 10, null));
        int feedCountBeforeShadow = count("feed_snapshots");

        ShadowRankingRun shadow = shadowRankingService.run(new ShadowRankingRunRequest(baseline.id(), "v1_graph_affinity", 10));
        assertThat(shadow.items()).isNotEmpty();
        assertThat(count("feed_snapshots")).isEqualTo(feedCountBeforeShadow);
        assertThat(shadow.items()).anySatisfy(item -> {
            assertThat(item.candidateProfileId()).isNotNull();
            assertThat(item.championPosition() != null || item.challengerPosition() != null).isTrue();
        });

        String ccKey = "mgrp023-cc";
        championChallengerService.create(new ChampionChallengerConfigRequest(ccKey, "MGRP023 CC", "ACTIVE", "v1_balanced", "v1_graph_affinity", Map.of("maxSafetyRegressionCount", 0)));
        ChampionChallengerDecision ccDecision = championChallengerService.evaluate(ccKey, new ChampionChallengerEvaluateRequest(baseline.id(), 10));
        assertThat(ccDecision.guardrailStatus()).isIn("PASS", "FAIL");
        assertThat(ccDecision.promotionRecommendation()).isIn("PROMOTE", "HOLD", "REJECT");

        CandidateExplanation shown = explainabilityService.whyShown(actor, feed.items().getFirst().candidateProfileId());
        assertThat(shown.result()).containsKeys("durableIds", "evidence", "reasons");
        Map<?, ?> shownEvidence = (Map<?, ?>) shown.result().get("evidence");
        assertThat(shownEvidence.keySet().stream().map(String::valueOf).toList())
            .contains("sourceTypes", "featureValues", "rankingReasons");

        graphEdgeService.block(actor, new GraphActionRequest(hidden, "integration hidden proof"));
        CandidateExplanation hiddenExplanation = explainabilityService.whyHidden(actor, hidden);
        assertThat(hiddenExplanation.reasons()).contains("BLOCKED_EITHER_DIRECTION");
        UUID blockedServedCandidate = feed.items().getFirst().candidateProfileId();
        graphEdgeService.block(actor, new GraphActionRequest(blockedServedCandidate, "integration feed refresh safety proof"));
        FeedSnapshot safeRefresh = feedService.refresh(actor, new FeedRefreshRequest(retrieval.id(), 10, null));
        assertThat(safeRefresh.items()).noneMatch(item -> item.candidateProfileId().equals(blockedServedCandidate));

        String banditKey = "mgrp025-bandit";
        banditPolicyService.create(new BanditPolicyRequest(
            banditKey,
            "MGRP025 Bandit",
            "ACTIVE",
            "EPSILON_GREEDY",
            BigDecimal.ZERO,
            Map.of("LIKE", 1, "PROFILE_VIEW", 0.25),
            Map.of(),
            List.of(
                new BanditArmRequest("graph", "GRAPH_TWO_HOP", "GRAPH_TWO_HOP boost", BigDecimal.ONE, Map.of()),
                new BanditArmRequest("vector", "VECTOR_SIMILARITY", "VECTOR_SIMILARITY boost", BigDecimal.ONE, Map.of())
            )
        ));
        BanditDecision banditDecision = banditDecisionService.decide(actor, banditKey, new BanditDecisionRequest(safeRefresh.items().getFirst().candidateProfileId(), null, "default", Map.of(
            "test", true,
            "HARD_EXCLUSIONS_ENFORCED", false,
            "applyToRanking", "tampered"
        ), true));
        assertThat(banditDecision.decisionContext())
            .containsEntry("HARD_EXCLUSIONS_ENFORCED", true)
            .containsEntry("applyToRanking", true)
            .containsEntry("applyToRankingMode", "DECISION_ONLY")
            .containsEntry("selectedArmKey", banditDecision.selectedArmKey())
            .containsEntry("contextSegment", "default")
            .containsKey("selectedArmStrategy")
            .containsKey("requestedCandidateProfileId");
        BanditReward reward = banditRewardService.reward(new BanditRewardRequest(banditDecision.id(), banditKey, actor, banditDecision.candidateProfileId(), "LIKE", null, null));
        assertThat(reward.rewardValue()).isPositive();
        assertThat((List<?>) banditPolicyService.summary(banditKey).get("stats")).isNotEmpty();

        String interleavingKey = "mgrp026-interleaving";
        interleavingService.createExperiment(new InterleavingExperimentRequest(interleavingKey, "MGRP026 Interleaving", "ACTIVE", "v1_balanced", "v1_graph_affinity", Map.of()));
        InterleavingSession session = interleavingService.createSession(actor, interleavingKey, new InterleavingSessionRequest(snapshots.id(), 10, baseline.rankingContext()));
        assertThat(session.items()).isNotEmpty();
        InterleavingOutcome outcome = interleavingService.outcome(session.id(), new InterleavingOutcomeRequest(session.items().getFirst().candidateProfileId(), null, "LIKE", BigDecimal.ONE));
        assertThat(outcome.winner()).isIn("A", "B", "TIE", "INSUFFICIENT_DATA");

        String longTailPolicy = "mgrp027-long-tail";
        ExposureControlPolicy exposurePolicy = exposurePolicyService.create(new ExposurePolicyRequest(longTailPolicy, "MGRP027 Long Tail", "ACTIVE", 100, 100, 24, 100, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.30), BigDecimal.valueOf(0.10), Map.of()));
        FeedSnapshot longTailFeed = feedService.refresh(actor, new FeedRefreshRequest(retrieval.id(), 10, null));
        assertThat(count("candidate_exposure_events")).isGreaterThan(0);
        assertThat(count("exposure_adjustments")).isGreaterThan(0);
        String capPolicy = "mgrp027-cap";
        exposurePolicyService.create(new ExposurePolicyRequest(capPolicy, "MGRP027 Cap", "ACTIVE", 0, 0, 24, 0, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.30), BigDecimal.valueOf(0.10), Map.of()));
        feedService.refresh(actor, new FeedRefreshRequest(retrieval.id(), 10, null));
        assertThat(jdbcTemplate.queryForList("select adjustment_reason from exposure_adjustments", String.class))
            .contains("LONG_TAIL_OR_NEW_PROFILE_BOOST")
            .contains("OVEREXPOSED_DOWNRANK");
        assertThat(jdbcTemplate.queryForList(
            "select count(*)::int from exposure_adjustments where candidate_profile_id = ? and boost_amount > 0",
            Integer.class,
            hidden
        ).getFirst()).isZero();
        assertThat(jdbcTemplate.queryForList(
            "select adjustment_reason from exposure_adjustments where candidate_profile_id = ?",
            String.class,
            blockedServedCandidate
        )).contains("HARD_EXCLUSION_DROPPED");

        SyntheticEvaluationRun syntheticEvaluation = groundTruthEvaluationService.evaluate(new SyntheticEvaluationRequest(population.id(), baseline.id(), baseline.rankingVersion(), 10));
        assertThat(syntheticEvaluation.result().precisionAtK()).isNotNull();
        assertThat(syntheticEvaluation.result().ndcgAtK()).isNotNull();
        assertThat(syntheticEvaluation.result().mrr()).isNotNull();
        assertThat(syntheticEvaluation.result().clusterCoverage()).isNotNull();
        assertThat(syntheticEvaluation.result().longTailCoverage()).isNotNull();
        assertThat(syntheticEvaluation.result().metrics())
            .containsEntry("safetyViolationEvidenceStatus", "VIOLATIONS_FOUND")
            .containsKey("evaluatedSafetyPairs");
        assertThat(syntheticEvaluation.result().safetyViolationCount()).isGreaterThan(0);

        assertThat(counterfactualEvaluationService.evaluate(new CounterfactualEvaluationRequest(baseline.id(), "v1_vector_affinity", 10)).items()).isNotEmpty();

        RankingScienceDemoReport report = demoService.run(new RankingScienceDemoRequest(23030L, 8, 2, Map.of("test", true)));
        assertThat(report.syntheticPopulationRunId()).isNotNull();
        assertThat(report.actorProfileId()).isNotNull();
        assertThat(report.retrievalRunId()).isNotNull();
        assertThat(report.featureSnapshotRunId()).isNotNull();
        assertThat(report.rankingDecisionLogId()).isNotNull();
        assertThat(report.feedSnapshotId()).isNotNull();
        assertThat(report.explanationRequestIds()).isNotEmpty();
        assertThat(report.durationByStep()).isNotEmpty();
        assertThat(report.criticalStepFailures()).isEmpty();
        assertThat(report.failedCriticalStepCount()).isZero();
        assertThat(report.completedStepCount()).isGreaterThanOrEqualTo(7);
        assertThat(report.skippedOptionalStepCount()).isEqualTo(report.optionalSkippedSteps().size());
        assertThat(report.optionalSkippedSteps()).allSatisfy(step ->
            assertThat(List.of("bandit", "interleaving", "exposure", "offline_evaluation", "counterfactual_evaluation"))
                .contains(String.valueOf(step.get("stepName")))
        );
        assertThat(demoService.get(report.demoRunId()).steps()).allSatisfy(step ->
            assertThat(step.stepStatus()).isIn("COMPLETED", "SKIPPED_OPTIONAL", "FAILED_CRITICAL")
        );

        assertThat(applicationContext.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase().contains("frontend"));
        assertThat(applicationContext.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase().contains("payment"));
        assertThat(applicationContext.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase().contains("marketplace"));
        assertThat(applicationContext.getBeanDefinitionNames()).filteredOn(name -> !name.startsWith("org.springframework")).noneMatch(name -> name.toLowerCase().contains("realtimecollab")
            || (name.toLowerCase().contains("websocket") && !name.equals("websocketServletWebServerCustomizer"))
            || name.toLowerCase().contains("crdt")
            || name.toLowerCase().contains("presence"));
        assertThat(applicationContext.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase().contains("kafkarelay")
            || name.toLowerCase().contains("relayconsumer"));
        assertThat(count("ranking_science_demo_steps")).isGreaterThan(0);
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject("select count(*)::int from " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private int labelCount(UUID runId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*)::int from synthetic_ground_truth_labels where run_id = ?",
            Integer.class,
            runId
        );
        return count == null ? 0 : count;
    }
}
