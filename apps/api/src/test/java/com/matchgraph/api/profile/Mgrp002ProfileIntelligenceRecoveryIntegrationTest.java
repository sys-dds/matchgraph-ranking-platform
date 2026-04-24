package com.matchgraph.api.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
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
class Mgrp002ProfileIntelligenceRecoveryIntegrationTest {

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
    void profileIntelligenceRecoveryFlowWorks() {
        ProfileResponse created = restTemplate.postForObject(
            "/api/v1/profiles",
            new CreateProfileRequest(
                "mgrp002-profile",
                "Ada Lovelace",
                "USER",
                "ACTIVE",
                "Builds ranking systems with care.",
                "Glasgow",
                "Scotland",
                "GB"
            ),
            ProfileResponse.class
        );

        assertThat(created.id()).isNotNull();
        assertThat(created.bio()).isEqualTo("Builds ranking systems with care.");
        assertThat(created.city()).isEqualTo("Glasgow");
        assertThat(created.region()).isEqualTo("Scotland");
        assertThat(created.country()).isEqualTo("GB");
        assertThat(created.profileCompletenessScore()).isGreaterThan(BigDecimal.ZERO);

        ProfileResponse updated = exchange(
            "/api/v1/profiles/" + created.id(),
            HttpMethod.PATCH,
            new UpdateProfileRequest(
                "Ada L.",
                "ACTIVE",
                "Updated profile intelligence bio.",
                "Edinburgh",
                "Scotland",
                "GB",
                null
            ),
            ProfileResponse.class
        ).getBody();

        assertThat(updated.displayName()).isEqualTo("Ada L.");
        assertThat(updated.bio()).isEqualTo("Updated profile intelligence bio.");
        assertThat(updated.profileCompletenessScore()).isGreaterThanOrEqualTo(created.profileCompletenessScore());

        ResponseEntity<ProfileInterestResponse[]> interestsResponse = exchange(
            "/api/v1/profiles/" + created.id() + "/interests",
            HttpMethod.PUT,
            new UpdateProfileInterestsRequest(List.of(
                new ProfileInterestRequest("language", "java", BigDecimal.valueOf(2)),
                new ProfileInterestRequest("location", "glasgow", BigDecimal.ONE)
            )),
            ProfileInterestResponse[].class
        );

        assertThat(interestsResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(Arrays.asList(interestsResponse.getBody()))
            .extracting(ProfileInterestResponse::interestValue)
            .contains("java", "glasgow");

        ResponseEntity<ProfileEmbeddingStatusResponse> validEmbedding = exchange(
            "/api/v1/profiles/" + created.id() + "/embedding",
            HttpMethod.PUT,
            new UpsertProfileEmbeddingRequest("profile-intel-v1", "test-profile-model", vector(384)),
            ProfileEmbeddingStatusResponse.class
        );

        assertThat(validEmbedding.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(validEmbedding.getBody().embeddingStatus()).isEqualTo("CURRENT");
        assertThat(validEmbedding.getBody().dimensions()).isEqualTo(384);
        assertThat(validEmbedding.getBody().activeVersionName()).isEqualTo("profile-intel-v1");

        exchange(
            "/api/v1/profiles/" + created.id(),
            HttpMethod.PATCH,
            new UpdateProfileRequest(null, "INACTIVE", null, null, null, null, null),
            ProfileResponse.class
        );
        assertThat(restTemplate.getForObject(
            "/api/v1/profiles/{profileId}/embedding/status",
            ProfileEmbeddingStatusResponse.class,
            created.id()
        ).embeddingStatus()).isEqualTo("CURRENT");

        exchange(
            "/api/v1/profiles/" + created.id(),
            HttpMethod.PATCH,
            new UpdateProfileRequest(null, null, null, null, null, null, OffsetDateTime.now()),
            ProfileResponse.class
        );
        assertThat(restTemplate.getForObject(
            "/api/v1/profiles/{profileId}/embedding/status",
            ProfileEmbeddingStatusResponse.class,
            created.id()
        ).embeddingStatus()).isEqualTo("CURRENT");

        exchange(
            "/api/v1/profiles/" + created.id(),
            HttpMethod.PATCH,
            new UpdateProfileRequest("Ada Semantic", "ACTIVE", null, null, null, null, null),
            ProfileResponse.class
        );
        assertThat(restTemplate.getForObject(
            "/api/v1/profiles/{profileId}/embedding/status",
            ProfileEmbeddingStatusResponse.class,
            created.id()
        ).embeddingStatus()).isEqualTo("STALE");

        exchange(
            "/api/v1/profiles/" + created.id() + "/embedding",
            HttpMethod.PUT,
            new UpsertProfileEmbeddingRequest("profile-intel-v1", "test-profile-model", vector(384)),
            ProfileEmbeddingStatusResponse.class
        );

        exchange(
            "/api/v1/profiles/" + created.id() + "/interests",
            HttpMethod.PUT,
            new UpdateProfileInterestsRequest(List.of(
                new ProfileInterestRequest("language", "java", BigDecimal.valueOf(2)),
                new ProfileInterestRequest("topic", "ranking", BigDecimal.ONE)
            )),
            ProfileInterestResponse[].class
        );

        ProfileEmbeddingStatusResponse staleStatus = restTemplate.getForObject(
            "/api/v1/profiles/{profileId}/embedding/status",
            ProfileEmbeddingStatusResponse.class,
            created.id()
        );
        assertThat(staleStatus.embeddingStatus()).isEqualTo("STALE");
        assertThat(staleStatus.activeVersionName()).isEqualTo("profile-intel-v1");

        ResponseEntity<Map> invalidEmbedding = exchange(
            "/api/v1/profiles/" + created.id() + "/embedding",
            HttpMethod.PUT,
            new UpsertProfileEmbeddingRequest("profile-intel-bad", "test-profile-model", vector(383)),
            Map.class
        );
        assertThat(invalidEmbedding.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<ProfileLocationResponse> location = exchange(
            "/api/v1/profiles/" + created.id() + "/location",
            HttpMethod.PUT,
            new UpdateProfileLocationRequest(
                BigDecimal.valueOf(55.9533),
                BigDecimal.valueOf(-3.1883),
                BigDecimal.valueOf(20),
                "Edinburgh",
                "Scotland",
                "GB"
            ),
            ProfileLocationResponse.class
        );
        assertThat(location.getBody().city()).isEqualTo("Edinburgh");
        assertThat(location.getBody().precisionKm()).isEqualByComparingTo("20");

        ProfileResponse publicRead = restTemplate.getForObject("/api/v1/profiles/{profileId}", ProfileResponse.class, created.id());
        assertThat(publicRead.location().city()).isEqualTo("Edinburgh");
        assertThat(publicRead.toString()).doesNotContain("55.9533", "-3.1883");

        ProfileSafetyStateResponse safety = restTemplate.getForObject(
            "/api/v1/profiles/{profileId}/safety",
            ProfileSafetyStateResponse.class,
            created.id()
        );
        assertThat(safety.profileId()).isEqualTo(created.id());
        assertThat(safety.safetyState()).isEqualTo("UNREVIEWED");

        assertPrematureEndpointNotExposed("/api/v1/feed/" + created.id());
        assertPrematureEndpointNotExposed("/api/v1/items/" + UUID.randomUUID());
        ResponseEntity<Map> interactionResponse = restTemplate.postForEntity(
            "/api/v1/interactions",
            Map.of("profileId", created.id(), "interactionType", "LIKE"),
            Map.class
        );
        assertThat(interactionResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(new ClassPathResource("db/migration/V1__foundation_schema.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V2__first_ranking_vertical_slice.sql").exists()).isTrue();
        assertThat(flywayVersionApplied("1")).isTrue();
        assertThat(flywayVersionApplied("2")).isTrue();
        assertThat(flywayVersionApplied("3")).isTrue();
    }

    private <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, Class<T> responseType) {
        return restTemplate.exchange(url, method, new HttpEntity<>(body), responseType);
    }

    private void assertPrematureEndpointNotExposed(String url) {
        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private boolean flywayVersionApplied(String version) {
        Boolean exists = jdbcTemplate.queryForObject(
            "select exists (select 1 from flyway_schema_history where version = ? and success)",
            Boolean.class,
            version
        );
        return Boolean.TRUE.equals(exists);
    }

    private List<Double> vector(int dimensions) {
        return IntStream.range(0, dimensions)
            .mapToDouble(index -> index / 1000.0d)
            .boxed()
            .toList();
    }
}
