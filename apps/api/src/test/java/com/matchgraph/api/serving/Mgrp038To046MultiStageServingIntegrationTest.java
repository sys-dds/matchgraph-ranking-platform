package com.matchgraph.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.feed.DiscoveryFeedService;
import com.matchgraph.api.feed.FeedRefreshRequest;
import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.graph.GraphEdgeService;
import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileResponse;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.profile.UpdateProfileLocationRequest;
import com.matchgraph.api.serving.ServingModels.MultiStageServingRequest;
import com.matchgraph.api.serving.ServingModels.MultiStageServingResponse;
import com.matchgraph.api.serving.ServingModels.RecommendationSurfaceRequest;
import com.matchgraph.api.serving.ServingModels.SessionIntentEvent;

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
class Mgrp038To046MultiStageServingIntegrationTest {

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
    RecommendationSurfaceService surfaceService;
    @Autowired
    SessionIntentService sessionIntentService;
    @Autowired
    MultiSurfaceRecommendationService servingService;
    @Autowired
    MultiStageServingDemoService demoService;
    @Autowired
    DiscoveryFeedService feedService;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void provesMultiStageServingPersistsEveryStageAndDegradesHonestly() {
        ProfileResponse actor = profile("mgrp038-actor");
        ProfileResponse candidateA = profile("mgrp038-a");
        ProfileResponse candidateB = profile("mgrp038-b");
        ProfileResponse candidateC = profile("mgrp038-c");
        graphEdgeService.follow(actor.id(), new GraphActionRequest(candidateA.id(), "direct"));
        graphEdgeService.follow(actor.id(), new GraphActionRequest(candidateB.id(), "direct"));
        graphEdgeService.follow(candidateA.id(), new GraphActionRequest(candidateC.id(), "two-hop"));

        surfaceService.create(new RecommendationSurfaceRequest(
            "HOME_FEED",
            "ENABLED",
            "v1_balanced",
            List.of("GRAPH_MUTUALS", "GRAPH_TWO_HOP", "COLD_START"),
            3,
            250,
            Map.of(),
            Map.of("maxSameSourceTopK", 2),
            Map.of("ruleRankingVersion", "v1_balanced"),
            Map.of("hardExclusions", "ALWAYS")
        ));

        var session = sessionIntentService.create(actor.id());
        sessionIntentService.record(session.id(), new SessionIntentEvent("LIKE", "GRAPH_MUTUALS", candidateA.id(), Map.of()));
        MultiStageServingResponse response = servingService.multiStage(
            actor.id(),
            "HOME_FEED",
            new MultiStageServingRequest(session.id(), 3, null, false, false, false)
        );
        assertThat(response.servedItems()).isNotEmpty();
        assertPersisted(response.requestId());
        assertThat(response.trace()).containsKeys("sourceRoutingPlanId", "preRankRunId", "heavyRankRunId", "slateOptimizationRunId", "servingQualityRunId");
        assertThat(String.valueOf(response.trace().get("sessionIntentAdjustments"))).contains("sourceWeights");

        MultiStageServingResponse timeout = servingService.multiStage(
            actor.id(),
            "HOME_FEED",
            new MultiStageServingRequest(session.id(), 3, null, true, false, false)
        );
        assertThat(timeout.degraded()).isTrue();
        assertThat(timeout.warnings()).anyMatch(warning -> warning.contains("source timeout"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from source_call_results where request_id = ? and timeout", Integer.class, timeout.requestId())).isGreaterThan(0);

        MultiStageServingResponse modelFallback = servingService.multiStage(
            actor.id(),
            "HOME_FEED",
            new MultiStageServingRequest(session.id(), 3, "ltr:model:v1", false, true, false)
        );
        assertThat(modelFallback.degraded()).isTrue();
        assertThat(jdbcTemplate.queryForObject("select count(*) from heavy_rank_runs where request_id = ? and fallback_used", Integer.class, modelFallback.requestId())).isGreaterThan(0);

        feedService.refresh(actor.id(), new FeedRefreshRequest(null, 3));
        UUID demoRunId = demoService.run(actor.id()).id();
        assertThat(jdbcTemplate.queryForObject("select count(*) from multi_stage_serving_demo_steps where demo_run_id = ? and trace_id is not null", Integer.class, demoRunId)).isGreaterThanOrEqualTo(7);
    }

    private void assertPersisted(UUID requestId) {
        assertThat(count("source_routing_plans", requestId)).isGreaterThan(0);
        assertThat(count("source_call_results", requestId)).isGreaterThan(0);
        assertThat(count("pre_rank_runs", requestId)).isGreaterThan(0);
        assertThat(count("pre_rank_items pri join pre_rank_runs pr on pr.id = pri.run_id", requestId)).isGreaterThan(0);
        assertThat(count("heavy_rank_runs", requestId)).isGreaterThan(0);
        assertThat(count("heavy_rank_items hri join heavy_rank_runs hr on hr.id = hri.run_id", requestId)).isGreaterThan(0);
        assertThat(count("slate_optimization_runs", requestId)).isGreaterThan(0);
        assertThat(count("slate_optimization_items soi join slate_optimization_runs so on so.id = soi.run_id", requestId)).isGreaterThan(0);
        assertThat(count("multi_stage_serving_trace_steps", requestId)).isGreaterThan(0);
        assertThat(count("serving_quality_runs", requestId)).isGreaterThan(0);
        assertThat(count("serving_quality_stage_metrics sqm join serving_quality_runs sq on sq.id = sqm.run_id", requestId)).isGreaterThan(0);
    }

    private int count(String tableExpression, UUID requestId) {
        return jdbcTemplate.queryForObject("select count(*) from " + tableExpression + " where request_id = ?", Integer.class, requestId);
    }

    private ProfileResponse profile(String key) {
        ProfileResponse profile = profileService.create(new CreateProfileRequest(key, key, "USER", "ACTIVE", "bio", "London", "London", "UK"));
        profileService.updateLocation(profile.id(), new UpdateProfileLocationRequest(BigDecimal.valueOf(51.5), BigDecimal.valueOf(-0.1), BigDecimal.valueOf(1), "London", "London", "UK"));
        return profile;
    }
}
