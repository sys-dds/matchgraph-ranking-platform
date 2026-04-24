package com.matchgraph.api.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.features.FeatureResponse;
import com.matchgraph.api.features.UpsertFeatureRequest;
import com.matchgraph.api.feed.CreateItemRequest;
import com.matchgraph.api.feed.ItemResponse;
import com.matchgraph.api.interaction.InteractionResponse;
import com.matchgraph.api.interaction.RecordInteractionRequest;
import com.matchgraph.api.matching.RankedFeedResponse;
import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
class FirstRankingVerticalSliceIntegrationTest {

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
    void firstRankingVerticalSliceWorksEndToEnd() {
        ProfileResponse profile = createProfile("profile-main");
        assertThat(profile.id()).isNotNull();
        assertThat(profile.profileType()).isEqualTo("USER");

        ItemResponse javaItem = createItem("item-java", "Java ranking post");
        ItemResponse cookingItem = createItem("item-cooking", "Cooking post");
        assertThat(javaItem.id()).isNotNull();

        FeatureResponse profileFeature = upsertProfileFeature(profile.id(), "language", "java", "2.0");
        FeatureResponse itemFeature = upsertItemFeature(javaItem.id(), "language", "java", "3.0");
        upsertItemFeature(cookingItem.id(), "topic", "food", "3.0");
        assertThat(profileFeature.featureKey()).isEqualTo("language");
        assertThat(itemFeature.featureValue()).isEqualTo("java");

        InteractionResponse interaction = recordInteraction(profile.id(), javaItem.id(), "LIKE");
        assertThat(interaction.interactionType()).isEqualTo("LIKE");
        assertThat(graphEdgeExists(profile.id(), javaItem.id(), "LIKED")).isTrue();

        RankedFeedResponse feed = restTemplate.getForObject("/api/v1/feed/{profileId}?limit=10", RankedFeedResponse.class, profile.id());
        assertThat(feed.candidates()).extracting(RankedCandidate::itemId).contains(javaItem.id(), cookingItem.id());
        assertThat(feed.candidates().getFirst().itemId()).isEqualTo(javaItem.id());
        assertThat(feed.candidates().getFirst().explanation().reasons())
            .contains("base:1.0", "feature:language=java:+6", "interaction:liked:+4");

        recordInteraction(profile.id(), javaItem.id(), "HIDE");
        RankedFeedResponse feedAfterHide = restTemplate.getForObject("/api/v1/feed/{profileId}?limit=10", RankedFeedResponse.class, profile.id());
        assertThat(feedAfterHide.candidates()).extracting(RankedCandidate::itemId).doesNotContain(javaItem.id());
    }

    @Test
    void invalidProfileIdReturnsNotFound() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/profiles/{id}", Map.class, UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidItemIdReturnsNotFound() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/items/{id}", Map.class, UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidInteractionTypeReturnsBadRequest() {
        ProfileResponse profile = createProfile("profile-invalid-interaction");
        ItemResponse item = createItem("item-invalid-interaction", "Invalid interaction post");

        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/v1/interactions",
            new RecordInteractionRequest(profile.id(), item.id(), "SHARE", null),
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void feedLimitDefaultsToTwentyAndRejectsValuesAboveHundred() {
        ProfileResponse profile = createProfile("profile-limit");
        for (int i = 0; i < 25; i++) {
            createItem("item-limit-" + i, "Limit item " + i);
        }

        RankedFeedResponse defaultFeed = restTemplate.getForObject("/api/v1/feed/{profileId}", RankedFeedResponse.class, profile.id());
        assertThat(defaultFeed.limit()).isEqualTo(20);
        assertThat(defaultFeed.candidates()).hasSize(20);

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/feed/{profileId}?limit=101", Map.class, profile.id());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listEndpointsFilterAndReturnCreatedResources() {
        ProfileResponse creator = createProfile("profile-list-creator", "CREATOR", "ACTIVE");
        ItemResponse product = createItem("item-list-product", "Product item", "PRODUCT", "ACTIVE");

        ResponseEntity<ProfileResponse[]> profiles = restTemplate.getForEntity(
            "/api/v1/profiles?profileType=CREATOR&status=ACTIVE&limit=50",
            ProfileResponse[].class
        );
        ResponseEntity<ItemResponse[]> items = restTemplate.getForEntity(
            "/api/v1/items?itemType=PRODUCT&status=ACTIVE&limit=50",
            ItemResponse[].class
        );

        assertThat(List.of(profiles.getBody())).extracting(ProfileResponse::id).contains(creator.id());
        assertThat(List.of(items.getBody())).extracting(ItemResponse::id).contains(product.id());
    }

    private ProfileResponse createProfile(String externalRef) {
        return createProfile(externalRef, "USER", "ACTIVE");
    }

    private ProfileResponse createProfile(String externalRef, String profileType, String status) {
        return restTemplate.postForObject(
            "/api/v1/profiles",
            new CreateProfileRequest(externalRef, "Test " + externalRef, profileType, status),
            ProfileResponse.class
        );
    }

    private ItemResponse createItem(String externalRef, String title) {
        return createItem(externalRef, title, "POST", "ACTIVE");
    }

    private ItemResponse createItem(String externalRef, String title, String itemType, String status) {
        return restTemplate.postForObject(
            "/api/v1/items",
            new CreateItemRequest(externalRef, title, itemType, status),
            ItemResponse.class
        );
    }

    private FeatureResponse upsertProfileFeature(UUID profileId, String key, String value, String weight) {
        restTemplate.put("/api/v1/profiles/{profileId}/features", new UpsertFeatureRequest(key, value, new BigDecimal(weight)), profileId);
        return restTemplate.getForObject("/api/v1/profiles/{profileId}/features", FeatureResponse[].class, profileId)[0];
    }

    private FeatureResponse upsertItemFeature(UUID itemId, String key, String value, String weight) {
        restTemplate.put("/api/v1/items/{itemId}/features", new UpsertFeatureRequest(key, value, new BigDecimal(weight)), itemId);
        return restTemplate.getForObject("/api/v1/items/{itemId}/features", FeatureResponse[].class, itemId)[0];
    }

    private InteractionResponse recordInteraction(UUID profileId, UUID itemId, String interactionType) {
        return restTemplate.postForObject(
            "/api/v1/interactions",
            new RecordInteractionRequest(profileId, itemId, interactionType, null),
            InteractionResponse.class
        );
    }

    private boolean graphEdgeExists(UUID profileId, UUID itemId, String edgeType) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                select exists (
                    select 1
                    from graph_edges
                    where source_profile_id = ?
                      and target_item_id = ?
                      and edge_type = ?
                )
                """,
            Boolean.class,
            profileId,
            itemId,
            edgeType
        );
        return Boolean.TRUE.equals(exists);
    }
}
