package com.matchgraph.api.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.interaction.RecordInteractionRequest;
import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileEmbeddingStatusResponse;
import com.matchgraph.api.profile.ProfileInterestRequest;
import com.matchgraph.api.profile.ProfileInterestResponse;
import com.matchgraph.api.profile.ProfileLocationResponse;
import com.matchgraph.api.profile.ProfileResponse;
import com.matchgraph.api.profile.UpdateProfileInterestsRequest;
import com.matchgraph.api.profile.UpdateProfileLocationRequest;
import com.matchgraph.api.profile.UpdateProfileRequest;
import com.matchgraph.api.profile.UpsertProfileEmbeddingRequest;

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
class Mgrp006007008009AdvancedRetrievalIntegrationTest {

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

    @Test
    void advancedRetrievalFlowWorks() {
        ProfileResponse actor = createProfile("mgrp006-actor", "Actor", "ACTIVE");
        ProfileResponse bridge = createProfile("mgrp006-bridge", "Bridge", "ACTIVE");
        ProfileResponse common = createProfile("mgrp006-common", "Common", "ACTIVE");
        ProfileResponse overlap = createProfile("mgrp006-overlap", "Overlap", "ACTIVE");
        ProfileResponse mutual = createProfile("mgrp006-mutual", "Mutual", "ACTIVE");
        ProfileResponse weakTie = createProfile("mgrp006-weak", "Weak Tie", "ACTIVE");
        ProfileResponse vectorSimilar = createProfile("mgrp006-vector", "Vector Similar", "ACTIVE");
        ProfileResponse nearby = createProfile("mgrp006-nearby", "Nearby", "ACTIVE");
        ProfileResponse blocked = createProfile("mgrp006-blocked", "Blocked", "ACTIVE");
        ProfileResponse reported = createProfile("mgrp006-reported", "Reported", "ACTIVE");
        ProfileResponse inactive = createProfile("mgrp006-inactive", "Inactive", "INACTIVE");
        ProfileResponse safetyBlocked = createProfile("mgrp006-safety-blocked", "Safety Blocked", "ACTIVE");

        List.of(actor, bridge, common, overlap, mutual, weakTie, vectorSimilar, nearby, blocked, reported, inactive, safetyBlocked)
            .forEach(profile -> putInterests(profile.id(), "retrieval"));

        updateLocation(actor.id(), BigDecimal.valueOf(55.8642), BigDecimal.valueOf(-4.2518), "Glasgow", "Scotland", "GB");
        updateLocation(overlap.id(), BigDecimal.valueOf(55.8650), BigDecimal.valueOf(-4.2500), "Glasgow", "Scotland", "GB");
        updateLocation(nearby.id(), BigDecimal.valueOf(55.9533), BigDecimal.valueOf(-3.1883), "Edinburgh", "Scotland", "GB");
        updateLocation(vectorSimilar.id(), BigDecimal.valueOf(55.8600), BigDecimal.valueOf(-4.2400), "Glasgow", "Scotland", "GB");

        putEmbedding(actor.id(), "advanced-v1", vector(0.10d));
        putEmbedding(overlap.id(), "advanced-v1", vector(0.11d));
        putEmbedding(vectorSimilar.id(), "advanced-v1", vector(0.12d));
        putEmbedding(nearby.id(), "advanced-v1", vector(0.50d));
        putEmbedding(blocked.id(), "advanced-v1", vector(0.13d));

        graph(actor.id(), "follow", bridge.id());
        graph(bridge.id(), "follow", overlap.id());
        graph(actor.id(), "follow", common.id());
        graph(mutual.id(), "follow", common.id());
        graph(actor.id(), "follow", weakTie.id());
        graph(actor.id(), "unfollow", weakTie.id());

        graph(actor.id(), "block", blocked.id());
        assertThat(graphStatus(actor.id(), "follow", blocked.id())).isEqualTo(HttpStatus.CONFLICT);
        assertThat(graphStatus(blocked.id(), "mute", actor.id())).isEqualTo(HttpStatus.CONFLICT);

        graph(actor.id(), "report", reported.id());
        jdbcTemplate.update(
            "update profile_safety_states set safety_state = 'BLOCKED', reason = 'test safety block', updated_at = now() where profile_id = ?",
            safetyBlocked.id()
        );

        ResponseEntity<Map> conflictingEvent = exchange(
            "/api/v1/profiles/" + actor.id() + "/interactions",
            HttpMethod.POST,
            new RecordInteractionRequest(
                "advanced-client-event",
                overlap.id(),
                "LIKE",
                OffsetDateTime.now(),
                null,
                null,
                "VECTOR_SIMILARITY",
                null,
                null,
                null,
                null,
                Map.of("intent", "first")
            ),
            Map.class
        );
        assertThat(conflictingEvent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> conflict = exchange(
            "/api/v1/profiles/" + actor.id() + "/interactions",
            HttpMethod.POST,
            new RecordInteractionRequest(
                "advanced-client-event",
                vectorSimilar.id(),
                "LIKE",
                OffsetDateTime.now(),
                null,
                null,
                "VECTOR_SIMILARITY",
                null,
                null,
                null,
                null,
                Map.of("intent", "different")
            ),
            Map.class
        );
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<CandidateSourceType, Integer> budgets = new EnumMap<>(CandidateSourceType.class);
        Arrays.stream(CandidateSourceType.values()).forEach(sourceType -> budgets.put(sourceType, 3));

        CandidateRetrievalRun retrievalRun = exchange(
            "/api/v1/profiles/" + actor.id() + "/retrieval/run",
            HttpMethod.POST,
            new RunRetrievalRequest(6, budgets, false),
            CandidateRetrievalRun.class
        ).getBody();

        assertThat(retrievalRun.candidates()).hasSizeLessThanOrEqualTo(6);
        assertThat(retrievalRun.sourceCoverage().get(CandidateSourceType.GRAPH_TWO_HOP)).isGreaterThan(0);
        assertThat(retrievalRun.sourceCoverage().get(CandidateSourceType.GRAPH_MUTUALS)).isGreaterThan(0);
        assertThat(retrievalRun.sourceCoverage().get(CandidateSourceType.WEAK_TIE_EXPLORATION)).isGreaterThan(0);
        assertThat(retrievalRun.sourceCoverage().get(CandidateSourceType.VECTOR_SIMILARITY)).isGreaterThan(0);
        assertThat(retrievalRun.sourceCoverage().get(CandidateSourceType.LOCATION_NEARBY)).isGreaterThan(0);
        assertThat(retrievalRun.sourceCoverage().values()).allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(3));
        assertThat(retrievalRun.sourceBudgets()).containsEntry(CandidateSourceType.VECTOR_SIMILARITY, 3);
        assertThat(retrievalRun.exclusionCounts()).containsKeys("BLOCKED_EITHER_DIRECTION", "ALREADY_REPORTED", "INACTIVE_PROFILE", "SUPPRESSED_PROFILE");
        assertThat(retrievalRun.retrievalQuality()).containsKeys(
            "rawCandidateCount",
            "dedupedCandidateCount",
            "finalCandidateCount",
            "excludedCandidateCount",
            "sourceCoverage",
            "sourceBudgets",
            "exclusionCounts",
            "emptySourceTypes",
            "saturatedSourceTypes"
        );
        assertThat(retrievalRun.candidates()).extracting(RetrievedCandidate::candidateProfileId)
            .doesNotContain(blocked.id(), reported.id(), inactive.id(), safetyBlocked.id());
        assertThat(retrievalRun.candidates()).anySatisfy(candidate -> {
            assertThat(candidate.candidateProfileId()).isEqualTo(overlap.id());
            assertThat(candidate.sourceTypes()).contains(
                CandidateSourceType.GRAPH_TWO_HOP,
                CandidateSourceType.VECTOR_SIMILARITY,
                CandidateSourceType.LOCATION_NEARBY
            );
            assertThat(candidate.sourceReason().toString()).contains("graphDistance", "vectorDistance", "distanceBand");
        });
        assertThat(retrievalRun.toString()).doesNotContain("rankingScore");

        CandidateRetrievalRun fetchedRun = restTemplate.getForObject(
            "/api/v1/profiles/{profileId}/retrieval/runs/{runId}",
            CandidateRetrievalRun.class,
            actor.id(),
            retrievalRun.id()
        );
        assertThat(fetchedRun.candidates()).hasSizeLessThanOrEqualTo(6);

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
                "Advanced retrieval profile.",
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

    private void updateLocation(UUID profileId, BigDecimal latitude, BigDecimal longitude, String city, String region, String country) {
        ResponseEntity<ProfileLocationResponse> response = exchange(
            "/api/v1/profiles/" + profileId + "/location",
            HttpMethod.PUT,
            new UpdateProfileLocationRequest(latitude, longitude, BigDecimal.valueOf(20), city, region, country),
            ProfileLocationResponse.class
        );
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private void putEmbedding(UUID profileId, String versionName, List<Double> embedding) {
        ResponseEntity<ProfileEmbeddingStatusResponse> response = exchange(
            "/api/v1/profiles/" + profileId + "/embedding",
            HttpMethod.PUT,
            new UpsertProfileEmbeddingRequest(versionName, "advanced-test-model", embedding),
            ProfileEmbeddingStatusResponse.class
        );
        assertThat(response.getBody().embeddingStatus()).isEqualTo("CURRENT");
    }

    private void graph(UUID sourceProfileId, String action, UUID targetProfileId) {
        assertThat(graphStatus(sourceProfileId, action, targetProfileId).is2xxSuccessful()).isTrue();
    }

    private HttpStatus graphStatus(UUID sourceProfileId, String action, UUID targetProfileId) {
        return HttpStatus.valueOf(exchange(
            "/api/v1/profiles/" + sourceProfileId + "/graph/" + action,
            HttpMethod.POST,
            new GraphActionRequest(targetProfileId, action + " reason"),
            Object.class
        ).getStatusCode().value());
    }

    private <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, Class<T> responseType) {
        return restTemplate.exchange(url, method, new HttpEntity<>(body), responseType);
    }

    private List<Double> vector(double seed) {
        return IntStream.range(0, 384)
            .mapToDouble(index -> seed + (index / 10000.0d))
            .boxed()
            .toList();
    }
}
