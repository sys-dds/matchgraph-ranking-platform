package com.matchgraph.api.finalproof;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileResponse;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.profile.UpdateProfileLocationRequest;
import com.matchgraph.api.realtime.CandidateInvalidationService;
import com.matchgraph.api.realtime.RealtimeInteractionService;
import com.matchgraph.api.realtime.RealtimeModels.CandidateInvalidationRequest;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionRequest;
import com.matchgraph.api.serving.HeavyRankService;
import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.streaming.CacheInvalidationGraphService;
import com.matchgraph.api.streaming.OnlineModelKillSwitchService;
import com.matchgraph.api.streaming.StreamingFeatureWindowService;
import com.matchgraph.api.streaming.StreamingModels.StreamingFeatureWindowRequest;

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
    @Autowired RealtimeInteractionService realtimeInteractionService;
    @Autowired StreamingFeatureWindowService windowService;
    @Autowired CandidateInvalidationService invalidationService;
    @Autowired OnlineModelKillSwitchService killSwitchService;
    @Autowired HeavyRankService heavyRankService;
    @Autowired CacheInvalidationGraphService cacheGraphService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void duplicateRealtimeEventDoesNotDoubleCountStreamingWindows() {
        ProfileResponse actor = profile("invariant-actor");
        ProfileResponse candidate = profile("invariant-candidate");
        String eventKey = "invariant-like-" + UUID.randomUUID();
        realtimeInteractionService.ingest(new RealtimeInteractionRequest(eventKey, actor.id(), candidate.id(), null, null, null, null, "LIKE", "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of()));
        realtimeInteractionService.ingest(new RealtimeInteractionRequest(eventKey, actor.id(), candidate.id(), null, null, null, null, "LIKE", "GRAPH_MUTUALS", OffsetDateTime.now(), Map.of("duplicate", true)));

        windowService.materialize(new StreamingFeatureWindowRequest(actor.id(), candidate.id(), "GRAPH_MUTUALS", null));

        assertThat(jdbcTemplate.queryForObject("select count(*) from realtime_interaction_events where event_key = ?", Integer.class, eventKey)).isEqualTo(1);
        assertThat(windowService.candidateWindows(candidate.id()).stream().filter(window -> "1h".equals(window.windowKey())).findFirst().orElseThrow().likes()).isEqualTo(1);
    }

    @Test
    void killedModelAndInvalidatedCandidateCannotBypassServingGuards() {
        ProfileResponse actor = profile("guard-actor");
        ProfileResponse candidate = profile("guard-candidate");
        invalidationService.create(new CandidateInvalidationRequest(actor.id(), candidate.id(), null, "REPORTED", true, null, Map.of("test", "hard safety")));
        killSwitchService.kill("guard-model", "v1", "invariant kill");

        var heavy = heavyRankService.rank(UUID.randomUUID(), "ltr:guard-model:v1", List.of(
            new CandidateItem(candidate.id(), "GRAPH_MUTUALS", BigDecimal.TEN, List.of(), false, null)
        ), false, false);
        var cacheRun = cacheGraphService.invalidate("CANDIDATE", candidate.id().toString(), false);

        assertThat(invalidationService.invalidated(actor.id(), candidate.id())).isTrue();
        assertThat(heavy.fallbackUsed()).isTrue();
        assertThat(heavy.fallbackReason()).contains("kill switch");
        assertThat(cacheRun.actions()).isNotEmpty();
        assertThat(cacheRun.actions()).allMatch(action -> !"GLOBAL_CLEAR".equals(action.actionType()));
    }

    private ProfileResponse profile(String key) {
        ProfileResponse profile = profileService.create(new CreateProfileRequest(key, key, "USER", "ACTIVE", "bio", "London", "London", "UK"));
        profileService.updateLocation(profile.id(), new UpdateProfileLocationRequest(BigDecimal.valueOf(51.5), BigDecimal.valueOf(-0.1), BigDecimal.ONE, "London", "London", "UK"));
        return profile;
    }
}
