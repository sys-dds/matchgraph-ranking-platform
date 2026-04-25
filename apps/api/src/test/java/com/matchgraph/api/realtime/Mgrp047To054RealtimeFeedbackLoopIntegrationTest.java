package com.matchgraph.api.realtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileResponse;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.profile.UpdateProfileLocationRequest;
import com.matchgraph.api.realtime.RealtimeModels.DeltaFeedRefreshRequest;
import com.matchgraph.api.realtime.RealtimeModels.FeatureFreshnessCheckRequest;
import com.matchgraph.api.realtime.RealtimeModels.NearlineFeatureMaterializationRequest;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionRequest;
import com.matchgraph.api.serving.MultiSurfaceRecommendationService;
import com.matchgraph.api.serving.RecommendationSurfaceService;
import com.matchgraph.api.serving.SessionIntentService;
import com.matchgraph.api.serving.ServingModels.MultiStageServingRequest;
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
class Mgrp047To054RealtimeFeedbackLoopIntegrationTest {

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
    MultiSurfaceRecommendationService servingService;
    @Autowired
    DiscoveryFeedService feedService;
    @Autowired
    SessionIntentService sessionIntentService;
    @Autowired
    RealtimeInteractionService interactionService;
    @Autowired
    NearlineFeatureMaterializerService nearlineService;
    @Autowired
    LiveSessionIntentService liveIntentService;
    @Autowired
    DeltaFeedRefreshService deltaFeedRefreshService;
    @Autowired
    OnlineFeatureFreshnessGuardService freshnessGuardService;
    @Autowired
    RealtimeFeedbackLoopDemoService demoService;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void provesRealtimeFeedbackLoopChangesServingFromDurableEvidence() {
        ProfileResponse actor = profile("mgrp047-actor");
        ProfileResponse candidateA = profile("mgrp047-a");
        ProfileResponse candidateB = profile("mgrp047-b");
        ProfileResponse candidateC = profile("mgrp047-c");
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
            Map.of(),
            Map.of(),
            Map.of("hardExclusions", "ALWAYS")
        ));

        var session = sessionIntentService.create(actor.id());
        FeedSnapshot feed = feedService.refresh(actor.id(), new FeedRefreshRequest(null, 5));
        var initial = servingService.multiStage(actor.id(), "HOME_FEED", new MultiStageServingRequest(session.id(), 3, null, false, false, false));
        assertThat(initial.servedItems()).isNotEmpty();
        UUID servedCandidate = initial.servedItems().getFirst().candidateProfileId();

        String eventKey = "mgrp047-pass-" + UUID.randomUUID();
        var pass = interactionService.ingest(new RealtimeInteractionRequest(eventKey, actor.id(), servedCandidate, feed.id(), null, initial.requestId(), session.id(), "pass", "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of("order", 1)));
        var duplicate = interactionService.ingest(new RealtimeInteractionRequest(eventKey, actor.id(), servedCandidate, feed.id(), null, initial.requestId(), session.id(), "PASS", "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of("order", 2)));
        assertThat(pass.duplicate()).isFalse();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.event().id()).isEqualTo(pass.event().id());
        assertThat(jdbcTemplate.queryForObject("select count(*) from realtime_interaction_events where event_key = ?", Integer.class, eventKey)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from realtime_candidate_invalidations where profile_id = ? and candidate_profile_id = ?", Integer.class, actor.id(), servedCandidate)).isGreaterThan(0);

        nearlineService.materialize(new NearlineFeatureMaterializationRequest(actor.id(), servedCandidate));
        assertThat(nearlineService.pair(actor.id(), servedCandidate).pairFeatures()).containsKey("recent_pass_count");

        var delta = deltaFeedRefreshService.refresh(actor.id(), feed.id(), new DeltaFeedRefreshRequest(pass.event().id(), initial.requestId(), session.id(), 2, "PASS removed candidate"));
        assertThat(delta.removedCandidates()).contains(servedCandidate);
        assertThat(delta.newCandidates()).doesNotContain(servedCandidate);

        interactionService.ingest(new RealtimeInteractionRequest("mgrp047-like-" + UUID.randomUUID(), actor.id(), candidateB.id(), feed.id(), null, null, session.id(), "LIKE", "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of()));
        sessionIntentService.record(session.id(), new SessionIntentEvent("LIKE", "GRAPH_MUTUALS", candidateB.id(), Map.of()));
        var live = liveIntentService.recompute(session.id());
        assertThat(live.sourceWeights()).containsKey("GRAPH_MUTUALS");
        var adapted = servingService.multiStage(actor.id(), "HOME_FEED", new MultiStageServingRequest(session.id(), 3, null, false, false, false));
        assertThat(String.valueOf(adapted.trace().get("sourceAdaptation"))).contains("baseBudgets");

        interactionService.ingest(new RealtimeInteractionRequest("mgrp047-block-" + UUID.randomUUID(), actor.id(), candidateA.id(), feed.id(), null, null, session.id(), "BLOCK", "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of()));
        var afterBlock = servingService.multiStage(actor.id(), "HOME_FEED", new MultiStageServingRequest(session.id(), 3, null, false, false, false));
        assertThat(afterBlock.servedItems()).noneMatch(item -> item.candidateProfileId().equals(candidateA.id()));

        var freshness = freshnessGuardService.check(new FeatureFreshnessCheckRequest(actor.id(), servedCandidate, List.of("recent_affinity_score"), 1L, false, true));
        assertThat(freshness.results()).anyMatch(result -> List.of("STALE", "MISSING", "DEGRADED").contains(result.status()));

        for (int i = 0; i < 3; i++) {
            servingService.multiStage(actor.id(), "HOME_FEED", new MultiStageServingRequest(session.id(), 3, null, false, false, false));
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from fatigue_suppression_windows where profile_id = ?", Integer.class, actor.id())).isGreaterThan(0);

        var demo = demoService.run(actor.id(), candidateB.id(), session.id(), feed.id());
        assertThat(demo.steps()).hasSize(6);
        assertThat(jdbcTemplate.queryForObject("select count(*) from realtime_feedback_loop_demo_steps where demo_run_id = ? and trace_id is not null", Integer.class, demo.demoRunId())).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("select count(*) from realtime_feedback_loop_trace_steps", Integer.class)).isGreaterThan(0);
    }

    private ProfileResponse profile(String key) {
        ProfileResponse profile = profileService.create(new CreateProfileRequest(key, key, "USER", "ACTIVE", "bio", "London", "London", "UK"));
        profileService.updateLocation(profile.id(), new UpdateProfileLocationRequest(BigDecimal.valueOf(51.5), BigDecimal.valueOf(-0.1), BigDecimal.valueOf(1), "London", "London", "UK"));
        return profile;
    }
}
