package com.matchgraph.api.ltr;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.causal.CausalEvaluationRequest;
import com.matchgraph.api.causal.CausalEvaluationRun;
import com.matchgraph.api.causal.CausalEvaluationService;
import com.matchgraph.api.causal.PropensityBackfillRequest;
import com.matchgraph.api.causal.PropensityLoggingService;
import com.matchgraph.api.featureparity.FeatureParityCheckRequest;
import com.matchgraph.api.featureparity.FeatureParityRun;
import com.matchgraph.api.featureparity.FeatureParityService;
import com.matchgraph.api.feed.DiscoveryFeedService;
import com.matchgraph.api.feed.FeedRefreshRequest;
import com.matchgraph.api.feed.FeedSnapshot;
import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.graph.GraphEdgeService;
import com.matchgraph.api.modelquality.CalibrationRequest;
import com.matchgraph.api.modelquality.ModelCalibrationService;
import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileResponse;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.profile.UpdateProfileLocationRequest;
import com.matchgraph.api.ranking.RankingDecision;
import com.matchgraph.api.ranking.RankingService;
import com.matchgraph.api.reward.LongTermRewardRequest;
import com.matchgraph.api.reward.LongTermRewardRun;
import com.matchgraph.api.reward.LongTermRewardService;
import com.matchgraph.api.rolloutgate.ModelRolloutGateRequest;
import com.matchgraph.api.rolloutgate.ModelRolloutGateRun;
import com.matchgraph.api.rolloutgate.ModelRolloutGateService;
import com.matchgraph.api.training.CreateTrainingDatasetRequest;
import com.matchgraph.api.training.TrainingDatasetRun;
import com.matchgraph.api.training.TrainingDatasetService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class Mgrp023To037LearningToRankCausalQualityIntegrationTest {

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

    @Autowired
    ProfileService profileService;
    @Autowired
    GraphEdgeService graphEdgeService;
    @Autowired
    DiscoveryFeedService feedService;
    @Autowired
    TrainingDatasetService trainingDatasetService;
    @Autowired
    LtrModelRegistryService modelRegistryService;
    @Autowired
    LtrTrainingService trainingService;
    @Autowired
    RankingService rankingService;
    @Autowired
    FeatureParityService featureParityService;
    @Autowired
    PropensityLoggingService propensityLoggingService;
    @Autowired
    CausalEvaluationService causalEvaluationService;
    @Autowired
    LongTermRewardService rewardService;
    @Autowired
    ModelRolloutGateService rolloutGateService;
    @Autowired
    ModelCalibrationService calibrationService;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void provesLearningToRankCausalRewardAndRolloutThroughSpringRuntime() {
        ProfileResponse actor = profile("mgrp030-actor");
        ProfileResponse positive = profile("mgrp030-positive");
        ProfileResponse negative = profile("mgrp030-negative");
        graphEdgeService.follow(actor.id(), new GraphActionRequest(positive.id(), "positive direct"));
        graphEdgeService.follow(actor.id(), new GraphActionRequest(negative.id(), "negative direct"));

        FeedSnapshot feed = feedService.refresh(actor.id(), new FeedRefreshRequest(null, 10));
        assertThat(feed.items()).isNotEmpty();
        recordInteraction(actor.id(), positive.id(), "LIKE");
        recordInteraction(actor.id(), negative.id(), "PASS");

        TrainingDatasetRun dataset = trainingDatasetService.create(new CreateTrainingDatasetRequest(
            "mgrp030-dataset-" + UUID.randomUUID(),
            OffsetDateTime.now().minusHours(2),
            OffsetDateTime.now().plusHours(1),
            72,
            true,
            false,
            100,
            Map.of()
        ));
        assertThat(trainingDatasetService.examples(dataset.id())).isNotEmpty();
        assertThat(trainingDatasetService.examples(dataset.id()).getFirst().servingFeatures()).isNotEmpty();
        assertThat(trainingDatasetService.examples(dataset.id()).getFirst().offlineFeatures()).isNotEmpty();

        modelRegistryService.createModel(new CreateLtrModelRequest("mgrp030-model", "MGRP 030 Model"));
        modelRegistryService.createVersion("mgrp030-model", new CreateLtrModelVersionRequest("v1", "LOCAL_LINEAR", "schema-v1", dataset.id(), Map.of()));
        LtrTrainingResponse training = trainingService.train(new LtrTrainingRequest(
            dataset.id(),
            "mgrp030-model",
            "v1",
            "LOCAL_LINEAR_WEIGHTED",
            List.of("shared_interest_count", "source_count"),
            0.5,
            7L,
            Map.of(),
            true
        ));
        assertThat(training.artifact().weights()).isNotEmpty();

        RankingDecision modelDecision = rankingService.run(actor.id(), feed.featureSnapshotRunId(), "ltr:mgrp030-model:v1", 5, "RANKING_RUN");
        assertThat(modelDecision.rankingContext()).containsEntry("modelKey", "mgrp030-model");
        assertThat(modelDecision.rankingContext()).containsEntry("versionKey", "v1");
        assertThat(modelDecision.rankingContext()).containsKey("modelVersionId");
        assertThat(modelDecision.items()).anySatisfy(item -> assertThat(item.reasons()).anyMatch(reason -> "MODEL_WEIGHTED_SCORE".equals(reason.reasonKey())));

        FeatureParityRun parity = featureParityService.check(new FeatureParityCheckRequest(dataset.id(), null, Map.of(), List.of("shared_interest_count"), 20));
        assertThat(parity.comparedCount()).isGreaterThan(0);
        assertThat(String.valueOf(calibrationService.calibrate(new CalibrationRequest("mgrp030-model", "v1", dataset.id(), 2)).summary())).contains("approximate");

        propensityLoggingService.backfill(new PropensityBackfillRequest(dataset.id()));
        CausalEvaluationRun causal = causalEvaluationService.evaluate(new CausalEvaluationRequest(dataset.id(), 5, true, 20.0));
        assertThat(causal.result().effectiveSampleSize()).isNotNull();
        assertThat(causal.result().propensityCoverage()).isNotNull();

        LongTermRewardRun reward = rewardService.create(new LongTermRewardRequest(dataset.id(), null, 72, true, true));
        assertThat(reward.result().labelledCount()).isGreaterThan(0);
        assertThat(String.valueOf(reward.result().summary())).contains("NOT_AVAILABLE");

        ModelRolloutGateRun gate = rolloutGateService.create(new ModelRolloutGateRequest("mgrp030-model", "v1", null, null, Map.of()));
        assertThat(gate.checks()).hasSizeGreaterThanOrEqualTo(12);
        assertThat(gate.recommendation()).isIn("APPROVE", "HOLD", "REJECT");
        assertThat(gate.checks()).anyMatch(check -> check.required() && "NOT_AVAILABLE".equals(check.status()));
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
            type + "-" + UUID.randomUUID(),
            actorId,
            candidateId,
            type
        );
    }

    private ProfileResponse profile(String key) {
        ProfileResponse profile = profileService.create(new CreateProfileRequest(key, key, "USER", "ACTIVE", "bio", "London", "London", "UK"));
        profileService.updateLocation(profile.id(), new UpdateProfileLocationRequest(BigDecimal.valueOf(51.5), BigDecimal.valueOf(-0.1), BigDecimal.valueOf(1), "London", "London", "UK"));
        return profile;
    }
}
