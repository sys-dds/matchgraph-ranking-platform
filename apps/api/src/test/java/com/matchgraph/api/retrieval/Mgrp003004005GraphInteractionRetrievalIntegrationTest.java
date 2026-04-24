package com.matchgraph.api.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.graph.GraphEdgeResponse;
import com.matchgraph.api.graph.GraphExclusionResponse;
import com.matchgraph.api.interaction.InteractionResponse;
import com.matchgraph.api.interaction.RecordInteractionRequest;
import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileInterestRequest;
import com.matchgraph.api.profile.ProfileInterestResponse;
import com.matchgraph.api.profile.ProfileResponse;
import com.matchgraph.api.profile.UpdateProfileInterestsRequest;
import com.matchgraph.api.profile.UpdateProfileRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
class Mgrp003004005GraphInteractionRetrievalIntegrationTest {

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

    @Test
    void graphInteractionRetrievalFlowWorks() {
        ProfileResponse actor = createProfile("mgrp003-actor", "Actor", "ACTIVE");
        ProfileResponse sharedCandidate = createProfile("mgrp003-shared", "Shared Candidate", "ACTIVE");
        ProfileResponse blockedCandidate = createProfile("mgrp003-blocked", "Blocked Candidate", "ACTIVE");
        ProfileResponse mutedCandidate = createProfile("mgrp003-muted", "Muted Candidate", "ACTIVE");
        ProfileResponse reportedCandidate = createProfile("mgrp003-reported", "Reported Candidate", "ACTIVE");
        ProfileResponse inactiveCandidate = createProfile("mgrp003-inactive", "Inactive Candidate", "INACTIVE");
        ProfileResponse interactionBlockedCandidate = createProfile("mgrp003-interaction-blocked", "Interaction Blocked", "ACTIVE");
        ProfileResponse interactionReportedCandidate = createProfile("mgrp003-interaction-reported", "Interaction Reported", "ACTIVE");

        putInterests(actor.id(), "java");
        putInterests(sharedCandidate.id(), "java");
        putInterests(blockedCandidate.id(), "java");
        putInterests(mutedCandidate.id(), "java");
        putInterests(reportedCandidate.id(), "java");
        putInterests(inactiveCandidate.id(), "java");
        putInterests(interactionBlockedCandidate.id(), "java");
        putInterests(interactionReportedCandidate.id(), "java");

        touchLastActive(sharedCandidate.id(), OffsetDateTime.now().minusMinutes(1));
        touchLastActive(blockedCandidate.id(), OffsetDateTime.now().minusMinutes(2));
        touchLastActive(mutedCandidate.id(), OffsetDateTime.now().minusMinutes(3));
        touchLastActive(reportedCandidate.id(), OffsetDateTime.now().minusMinutes(4));
        touchLastActive(inactiveCandidate.id(), OffsetDateTime.now().minusMinutes(5));

        GraphEdgeResponse follow = graph(actor.id(), "follow", sharedCandidate.id());
        GraphEdgeResponse duplicateFollow = graph(actor.id(), "follow", sharedCandidate.id());
        assertThat(duplicateFollow.id()).isEqualTo(follow.id());

        ResponseEntity<Map> selfEdge = exchange(
            "/api/v1/profiles/" + actor.id() + "/graph/follow",
            HttpMethod.POST,
            new GraphActionRequest(actor.id(), "self"),
            Map.class
        );
        assertThat(selfEdge.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        graph(actor.id(), "block", blockedCandidate.id());
        assertThat(exchange(
            "/api/v1/profiles/" + actor.id() + "/graph/follow",
            HttpMethod.POST,
            new GraphActionRequest(blockedCandidate.id(), "blocked follow"),
            Map.class
        ).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange(
            "/api/v1/profiles/" + blockedCandidate.id() + "/graph/mute",
            HttpMethod.POST,
            new GraphActionRequest(actor.id(), "blocked mute"),
            Map.class
        ).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        graph(actor.id(), "mute", mutedCandidate.id());
        graph(actor.id(), "report", reportedCandidate.id());

        InteractionResponse like = recordInteraction(
            actor.id(),
            new RecordInteractionRequest(
                "client-like-1",
                sharedCandidate.id(),
                "LIKE",
                OffsetDateTime.now(),
                "request-1",
                null,
                "SHARED_INTEREST",
                "future-ranking-version",
                "future-experiment",
                "variant-a",
                1,
                Map.of("surface", "test")
            )
        );
        InteractionResponse duplicateLike = recordInteraction(
            actor.id(),
            new RecordInteractionRequest(
                "client-like-1",
                sharedCandidate.id(),
                "LIKE",
                OffsetDateTime.now(),
                "request-1",
                null,
                "SHARED_INTEREST",
                "future-ranking-version",
                "future-experiment",
                "variant-a",
                1,
                Map.of("surface", "test")
            )
        );
        assertThat(duplicateLike.id()).isEqualTo(like.id());
        assertThat(like.candidateSource()).isEqualTo("SHARED_INTEREST");
        assertThat(like.rankingVersion()).isEqualTo("future-ranking-version");
        ResponseEntity<Map> conflictingLike = exchange(
            "/api/v1/profiles/" + actor.id() + "/interactions",
            HttpMethod.POST,
            new RecordInteractionRequest(
                "client-like-1",
                blockedCandidate.id(),
                "LIKE",
                OffsetDateTime.now(),
                "request-1",
                null,
                "SHARED_INTEREST",
                "future-ranking-version",
                "future-experiment",
                "variant-a",
                1,
                Map.of("surface", "test")
            ),
            Map.class
        );
        assertThat(conflictingLike.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        recordInteraction(actor.id(), new RecordInteractionRequest(
            "client-block-1",
            interactionBlockedCandidate.id(),
            "BLOCK",
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
        recordInteraction(actor.id(), new RecordInteractionRequest(
            "client-report-1",
            interactionReportedCandidate.id(),
            "REPORT",
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

        List<GraphExclusionResponse> exclusions = Arrays.asList(restTemplate.getForObject(
            "/api/v1/profiles/{profileId}/graph/exclusions",
            GraphExclusionResponse[].class,
            actor.id()
        ));
        assertThat(exclusions)
            .extracting(GraphExclusionResponse::profileId)
            .contains(blockedCandidate.id(), mutedCandidate.id(), reportedCandidate.id(), interactionBlockedCandidate.id(), interactionReportedCandidate.id());

        CandidateRetrievalRun retrievalRun = exchange(
            "/api/v1/profiles/" + actor.id() + "/retrieval/run",
            HttpMethod.POST,
            new RunRetrievalRequest(20, null, null),
            CandidateRetrievalRun.class
        ).getBody();

        assertThat(retrievalRun.status()).isEqualTo("COMPLETED");
        assertThat(retrievalRun.candidates()).extracting(RetrievedCandidate::candidateProfileId).contains(sharedCandidate.id());
        assertThat(retrievalRun.candidates()).extracting(RetrievedCandidate::candidateProfileId)
            .doesNotContain(blockedCandidate.id(), reportedCandidate.id(), inactiveCandidate.id(), interactionBlockedCandidate.id(), interactionReportedCandidate.id());
        assertThat(retrievalRun.candidates())
            .allSatisfy(candidate -> assertThat(candidate.sourceTypes()).isNotEmpty());
        assertThat(retrievalRun.sourceCoverage()).containsKeys(
            CandidateSourceType.RECENTLY_ACTIVE,
            CandidateSourceType.SHARED_INTEREST,
            CandidateSourceType.COLD_START
        );
        assertThat(retrievalRun.exclusionCount()).isGreaterThanOrEqualTo(5);
        assertThat(retrievalRun.toString()).doesNotContain("score");

        CandidateRetrievalRun limitedRun = exchange(
            "/api/v1/profiles/" + actor.id() + "/retrieval/run",
            HttpMethod.POST,
            new RunRetrievalRequest(2, null, null),
            CandidateRetrievalRun.class
        ).getBody();
        assertThat(limitedRun.candidates()).hasSizeLessThanOrEqualTo(2);

        CandidateRetrievalRun fetchedRun = restTemplate.getForObject(
            "/api/v1/profiles/{profileId}/retrieval/runs/{runId}",
            CandidateRetrievalRun.class,
            actor.id(),
            limitedRun.id()
        );
        assertThat(fetchedRun.id()).isEqualTo(limitedRun.id());
        assertThat(fetchedRun.candidates()).hasSizeLessThanOrEqualTo(2);
        assertThat(fetchedRun.candidates()).extracting(RetrievedCandidate::candidateProfileId).contains(sharedCandidate.id());

        ResponseEntity<Map> feedResponse = restTemplate.getForEntity("/api/v1/feed/" + actor.id(), Map.class);
        assertThat(feedResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ProfileResponse createProfile(String externalRef, String displayName, String status) {
        return restTemplate.postForObject(
            "/api/v1/profiles",
            new CreateProfileRequest(
                externalRef,
                displayName,
                "USER",
                status,
                "Profile for graph interaction retrieval test.",
                "Glasgow",
                "Scotland",
                "GB"
            ),
            ProfileResponse.class
        );
    }

    private void putInterests(UUID profileId, String interest) {
        ResponseEntity<ProfileInterestResponse[]> response = exchange(
            "/api/v1/profiles/" + profileId + "/interests",
            HttpMethod.PUT,
            new UpdateProfileInterestsRequest(List.of(new ProfileInterestRequest("topic", interest, BigDecimal.ONE))),
            ProfileInterestResponse[].class
        );
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private void touchLastActive(UUID profileId, OffsetDateTime lastActiveAt) {
        exchange(
            "/api/v1/profiles/" + profileId,
            HttpMethod.PATCH,
            new UpdateProfileRequest(null, null, null, null, null, null, lastActiveAt),
            ProfileResponse.class
        );
    }

    private GraphEdgeResponse graph(UUID sourceProfileId, String action, UUID targetProfileId) {
        return exchange(
            "/api/v1/profiles/" + sourceProfileId + "/graph/" + action,
            HttpMethod.POST,
            new GraphActionRequest(targetProfileId, action + " reason"),
            GraphEdgeResponse.class
        ).getBody();
    }

    private InteractionResponse recordInteraction(UUID actorProfileId, RecordInteractionRequest request) {
        return exchange(
            "/api/v1/profiles/" + actorProfileId + "/interactions",
            HttpMethod.POST,
            request,
            InteractionResponse.class
        ).getBody();
    }

    private <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, Class<T> responseType) {
        return restTemplate.exchange(url, method, new HttpEntity<>(body), responseType);
    }
}
