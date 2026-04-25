package com.matchgraph.api.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.matchgraph.api.features.CandidateFeatureSnapshot;
import com.matchgraph.api.features.CandidateFeatureValue;
import com.matchgraph.api.features.FeatureSnapshotRun;
import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.matching.MatchResponse;
import com.matchgraph.api.matching.MatchingService;
import com.matchgraph.api.matching.SwipeRequest;
import com.matchgraph.api.matching.SwipeResponse;
import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileInterestRequest;
import com.matchgraph.api.profile.ProfileInterestResponse;
import com.matchgraph.api.profile.ProfileResponse;
import com.matchgraph.api.profile.UpdateProfileInterestsRequest;
import com.matchgraph.api.profile.UpdateProfileLocationRequest;
import com.matchgraph.api.profile.UpdateProfileRequest;
import com.matchgraph.api.profile.UpsertProfileEmbeddingRequest;
import com.matchgraph.api.ranking.RankingDecision;
import com.matchgraph.api.ranking.RankingDecisionItem;
import com.matchgraph.api.ranking.RankingReason;
import com.matchgraph.api.ranking.RankingRunRequest;
import com.matchgraph.api.retrieval.CandidateRetrievalRun;
import com.matchgraph.api.retrieval.RunRetrievalRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Mgrp010To015RankingFeedMatchingIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE =
        DockerImageName.parse("garapadev/postgres-postgis-pgvector:16-optimized")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
        .withDatabaseName("matchgraph")
        .withUsername("matchgraph")
        .withPassword("matchgraph");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MatchingService matchingService;

    @Test
    void rankingFeedDecisionLogsAndMatchingFlowWorks() throws Exception {
        ProfileResponse actor = createProfile("mgrp010-actor", "Actor", "Glasgow");
        ProfileResponse graphVectorLocal = createProfile("mgrp010-candidate-a", "Graph Vector Local", "Glasgow");
        ProfileResponse regional = createProfile("mgrp010-candidate-b", "Regional", "Edinburgh");
        ProfileResponse coldStart = createProfile("mgrp010-candidate-c", "Cold Start", "Leeds");
        ProfileResponse repeated = createProfile("mgrp010-candidate-d", "Repeated", "Glasgow");
        ProfileResponse blocked = createProfile("mgrp010-candidate-blocked", "Blocked", "Glasgow");

        putInterests(actor.id(), "ranking", "java");
        putInterests(graphVectorLocal.id(), "ranking", "java");
        putInterests(regional.id(), "ranking", "postgres");
        putInterests(coldStart.id(), "search", "java");
        putInterests(repeated.id(), "ranking", "java");
        putInterests(blocked.id(), "ranking", "java");

        putLocation(actor.id(), BigDecimal.valueOf(55.8642), BigDecimal.valueOf(-4.2518), "Glasgow");
        putLocation(graphVectorLocal.id(), BigDecimal.valueOf(55.8600), BigDecimal.valueOf(-4.2500), "Glasgow");
        putLocation(regional.id(), BigDecimal.valueOf(55.9533), BigDecimal.valueOf(-3.1883), "Edinburgh");
        putLocation(coldStart.id(), BigDecimal.valueOf(53.8008), BigDecimal.valueOf(-1.5491), "Leeds");
        putLocation(repeated.id(), BigDecimal.valueOf(55.8700), BigDecimal.valueOf(-4.2600), "Glasgow");
        putLocation(blocked.id(), BigDecimal.valueOf(55.8500), BigDecimal.valueOf(-4.2400), "Glasgow");

        putEmbedding(actor.id(), 0.001);
        putEmbedding(graphVectorLocal.id(), 0.001);
        putEmbedding(regional.id(), 0.002);
        putEmbedding(coldStart.id(), 0.004);
        putEmbedding(repeated.id(), 0.006);
        putEmbedding(blocked.id(), 0.003);

        touchLastActive(graphVectorLocal.id(), OffsetDateTime.now().minusMinutes(5));
        touchLastActive(regional.id(), OffsetDateTime.now().minusHours(3));
        touchLastActive(coldStart.id(), OffsetDateTime.now().minusHours(6));
        touchLastActive(repeated.id(), OffsetDateTime.now().minusHours(1));
        graph(actor.id(), graphVectorLocal.id());
        graph(actor.id(), repeated.id());

        CandidateRetrievalRun retrievalRun = exchange(
            "/api/v1/profiles/" + actor.id() + "/retrieval/run",
            HttpMethod.POST,
            new RunRetrievalRequest(20),
            CandidateRetrievalRun.class
        ).getBody();
        assertThat(retrievalRun.status()).isEqualTo("COMPLETED");

        ResponseEntity<FeatureSnapshotRun> snapshotResponse = exchange(
            "/api/v1/profiles/" + actor.id() + "/feature-snapshots/from-retrieval/" + retrievalRun.id(),
            HttpMethod.POST,
            null,
            FeatureSnapshotRun.class
        );
        assertThat(snapshotResponse.getStatusCode())
            .as("feature snapshot response for retrievalRunId=%s", retrievalRun.id())
            .isEqualTo(HttpStatus.OK);
        FeatureSnapshotRun snapshotRun = snapshotResponse.getBody();
        assertThat(snapshotRun).isNotNull();
        assertThat(snapshotRun.candidates()).isNotEmpty();
        CandidateFeatureSnapshot graphSnapshot = snapshotFor(snapshotRun, graphVectorLocal.id());
        assertFeatureKeys(graphSnapshot, "shared_interest_count", "graph_distance", "vector_distance", "distance_band", "candidate_source_set", "safety_state");
        assertThat(value(graphSnapshot, "safety_state").textValue()).isEqualTo("UNREVIEWED");

        RankingDecision ranking = exchange(
            "/api/v1/profiles/" + actor.id() + "/ranking/run",
            HttpMethod.POST,
            new RankingRunRequest(snapshotRun.id(), "v1_balanced", 20),
            RankingDecision.class
        ).getBody();
        assertThat(ranking.rankingVersion()).isEqualTo("v1_balanced");
        assertThat(ranking.items()).isNotEmpty();
        ranking.items().forEach(this::assertReasonsSumToBaseScore);
        assertThat(ranking.items()).anySatisfy(item -> assertThat(item.diversityAdjustments()).isNotEmpty());

        FeedSnapshot feedSnapshot = exchange(
            "/api/v1/profiles/" + actor.id() + "/feed/discovery/refresh",
            HttpMethod.POST,
            new FeedRefreshRequest(retrievalRun.id(), 3),
            FeedSnapshot.class
        ).getBody();
        assertThat(feedSnapshot.items()).hasSizeLessThanOrEqualTo(3);
        assertThat(feedSnapshot.retrievalRunId()).isEqualTo(retrievalRun.id());
        assertThat(feedSnapshot.rankingDecisionLogId()).isNotNull();
        assertThat(feedSnapshot.items())
            .allSatisfy(item -> {
                assertThat(item.retrievalRunId()).isEqualTo(feedSnapshot.retrievalRunId());
                assertThat(item.rankingDecisionLogId()).isEqualTo(feedSnapshot.rankingDecisionLogId());
                assertThat(item.featureSnapshotId()).isNotNull();
            });

        FeedPage firstPage = restTemplate.getForObject(
            "/api/v1/profiles/{profileId}/feed/discovery?limit=2",
            FeedPage.class,
            actor.id()
        );
        FeedPage secondPage = restTemplate.getForObject(
            "/api/v1/profiles/{profileId}/feed/discovery?limit=2&cursor={cursor}",
            FeedPage.class,
            actor.id(),
            firstPage.nextCursor()
        );
        assertThat(firstPage.items()).extracting(FeedItem::candidateProfileId)
            .doesNotContainAnyElementsOf(secondPage.items().stream().map(FeedItem::candidateProfileId).toList());
        RankingDecision feedDecisionBeforeRead = restTemplate.getForObject(
            "/api/v1/ranking-decisions/{decisionLogId}",
            RankingDecision.class,
            feedSnapshot.rankingDecisionLogId()
        );
        restTemplate.getForObject("/api/v1/profiles/{profileId}/feed/discovery?limit=2", FeedPage.class, actor.id());
        RankingDecision feedDecisionAfterRead = restTemplate.getForObject(
            "/api/v1/ranking-decisions/{decisionLogId}",
            RankingDecision.class,
            feedSnapshot.rankingDecisionLogId()
        );
        assertThat(feedDecisionAfterRead.id()).isEqualTo(feedDecisionBeforeRead.id());

        RankingDecision decisionLog = restTemplate.getForObject(
            "/api/v1/ranking-decisions/{decisionLogId}",
            RankingDecision.class,
            feedSnapshot.rankingDecisionLogId()
        );
        RankingDecision replay = exchange(
            "/api/v1/ranking-decisions/" + feedSnapshot.rankingDecisionLogId() + "/replay",
            HttpMethod.POST,
            null,
            RankingDecision.class
        ).getBody();
        assertThat(replay.items()).extracting(RankingDecisionItem::candidateProfileId)
            .containsExactlyElementsOf(decisionLog.items().stream().map(RankingDecisionItem::candidateProfileId).toList());

        graph(actor.id(), blocked.id(), "block");
        FeedSnapshot refreshedAfterBlock = exchange(
            "/api/v1/profiles/" + actor.id() + "/feed/discovery/refresh",
            HttpMethod.POST,
            null,
            FeedSnapshot.class
        ).getBody();
        assertThat(refreshedAfterBlock.items()).extracting(FeedItem::candidateProfileId).doesNotContain(blocked.id());

        SwipeResponse actorSwipe = swipe(actor.id(), graphVectorLocal.id(), "RIGHT", "swipe-a-right-1");
        assertThat(actorSwipe.matchCreated()).isFalse();
        assertThat(rightSwipeCount(actor.id(), graphVectorLocal.id())).isEqualTo(1);
        SwipeResponse reciprocalSwipe = swipe(graphVectorLocal.id(), actor.id(), "RIGHT", "swipe-b-right-1");
        assertThat(rightSwipeCount(actor.id(), graphVectorLocal.id())).isEqualTo(2);
        assertThat(reciprocalSwipe.match()).isNotNull();
        assertThat(matches(actor.id())).hasSize(1);
        SwipeResponse duplicateSwipe = swipe(graphVectorLocal.id(), actor.id(), "RIGHT", "swipe-b-right-1");
        assertThat(duplicateSwipe.duplicate()).isTrue();
        assertThat(matches(actor.id())).hasSize(1);

        ProfileResponse concurrentA = createProfile("mgrp010-concurrent-a", "Concurrent A", "Perth");
        ProfileResponse concurrentB = createProfile("mgrp010-concurrent-b", "Concurrent B", "Dundee");
        runConcurrentReciprocalSwipes(concurrentA.id(), concurrentB.id());
        assertThat(matches(concurrentA.id())).hasSize(1);

        ProfileResponse safetyBlocked = createProfile("mgrp010-safety-blocked", "Safety Blocked", "Stirling");
        jdbcTemplate.update(
            "update profile_safety_states set safety_state = 'BLOCKED', reason = 'test', updated_at = now() where profile_id = ?",
            safetyBlocked.id()
        );
        ResponseEntity<Map> blockedMatch = exchange(
            "/api/v1/profiles/" + actor.id() + "/swipes",
            HttpMethod.POST,
            new SwipeRequest(safetyBlocked.id(), "RIGHT", "safety-blocked-swipe"),
            Map.class
        );
        assertThat(blockedMatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(restTemplate.getForEntity("/api/v1/experiments", Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ProfileResponse createProfile(String externalRef, String displayName, String city) {
        return restTemplate.postForObject(
            "/api/v1/profiles",
            new CreateProfileRequest(externalRef, displayName, "USER", "ACTIVE", "Integration profile.", city, "Scotland", "GB"),
            ProfileResponse.class
        );
    }

    private void putInterests(UUID profileId, String... interests) {
        List<ProfileInterestRequest> requests = Arrays.stream(interests)
            .map(interest -> new ProfileInterestRequest("topic", interest, BigDecimal.ONE))
            .toList();
        exchange(
            "/api/v1/profiles/" + profileId + "/interests",
            HttpMethod.PUT,
            new UpdateProfileInterestsRequest(requests),
            ProfileInterestResponse[].class
        );
    }

    private void putLocation(UUID profileId, BigDecimal latitude, BigDecimal longitude, String city) {
        exchange(
            "/api/v1/profiles/" + profileId + "/location",
            HttpMethod.PUT,
            new UpdateProfileLocationRequest(latitude, longitude, BigDecimal.valueOf(20), city, "Scotland", "GB"),
            Map.class
        );
    }

    private void putEmbedding(UUID profileId, double seed) {
        exchange(
            "/api/v1/profiles/" + profileId + "/embedding",
            HttpMethod.PUT,
            new UpsertProfileEmbeddingRequest("mgrp010-test-v1", "test-model", vector(seed)),
            Map.class
        );
    }

    private void touchLastActive(UUID profileId, OffsetDateTime lastActiveAt) {
        exchange(
            "/api/v1/profiles/" + profileId,
            HttpMethod.PATCH,
            new UpdateProfileRequest(null, null, null, null, null, null, lastActiveAt),
            ProfileResponse.class
        );
    }

    private void graph(UUID sourceProfileId, UUID targetProfileId) {
        graph(sourceProfileId, targetProfileId, "follow");
    }

    private void graph(UUID sourceProfileId, UUID targetProfileId, String action) {
        exchange(
            "/api/v1/profiles/" + sourceProfileId + "/graph/" + action,
            HttpMethod.POST,
            new GraphActionRequest(targetProfileId, action),
            Map.class
        );
    }

    private CandidateFeatureSnapshot snapshotFor(FeatureSnapshotRun run, UUID candidateId) {
        return run.candidates().stream()
            .filter(snapshot -> snapshot.candidateProfileId().equals(candidateId))
            .findFirst()
            .orElseThrow();
    }

    private void assertFeatureKeys(CandidateFeatureSnapshot snapshot, String... keys) {
        assertThat(snapshot.values()).extracting(CandidateFeatureValue::featureKey).contains(keys);
    }

    private CandidateFeatureValue value(CandidateFeatureSnapshot snapshot, String key) {
        return snapshot.values().stream()
            .filter(value -> value.featureKey().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private void assertReasonsSumToBaseScore(RankingDecisionItem item) {
        BigDecimal sum = item.reasons().stream()
            .map(RankingReason::scoreDelta)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(6);
        assertThat(sum).isEqualByComparingTo(item.baseScore());
    }

    private SwipeResponse swipe(UUID actorId, UUID targetId, String direction, String clientEventId) {
        ResponseEntity<SwipeResponse> response = exchange(
            "/api/v1/profiles/" + actorId + "/swipes",
            HttpMethod.POST,
            new SwipeRequest(targetId, direction, clientEventId),
            SwipeResponse.class
        );
        assertThat(response.getStatusCode())
            .as("swipe response actor=%s target=%s clientEventId=%s", actorId, targetId, clientEventId)
            .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private int rightSwipeCount(UUID firstProfileId, UUID secondProfileId) {
        Integer count = jdbcTemplate.queryForObject(
            """
                select count(*)::int
                from swipes
                where direction = 'RIGHT'
                  and (
                    (actor_profile_id = ? and target_profile_id = ?)
                    or (actor_profile_id = ? and target_profile_id = ?)
                  )
                """,
            Integer.class,
            firstProfileId,
            secondProfileId,
            secondProfileId,
            firstProfileId
        );
        return count == null ? 0 : count;
    }

    private List<MatchResponse> matches(UUID profileId) {
        return Arrays.asList(restTemplate.getForObject("/api/v1/profiles/{profileId}/matches", MatchResponse[].class, profileId));
    }

    private void runConcurrentReciprocalSwipes(UUID firstProfileId, UUID secondProfileId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<SwipeResponse> first = executor.submit(() -> {
                await(start);
                return matchingService.swipe(firstProfileId, new SwipeRequest(secondProfileId, "RIGHT", "concurrent-a"));
            });
            Future<SwipeResponse> second = executor.submit(() -> {
                await(start);
                return matchingService.swipe(secondProfileId, new SwipeRequest(firstProfileId, "RIGHT", "concurrent-b"));
            });
            start.countDown();
            assertThat(first.get()).isNotNull();
            assertThat(second.get()).isNotNull();
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private List<Double> vector(double seed) {
        return IntStream.range(0, 384)
            .mapToDouble(index -> seed + (index / 1000.0d))
            .boxed()
            .toList();
    }

    private <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, Class<T> responseType) {
        return restTemplate.exchange(url, method, new HttpEntity<>(body), responseType);
    }
}
