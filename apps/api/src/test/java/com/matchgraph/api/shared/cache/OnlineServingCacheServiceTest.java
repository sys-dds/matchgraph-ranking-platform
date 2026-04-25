package com.matchgraph.api.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matchgraph.api.feed.FeedPage;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class OnlineServingCacheServiceTest {

    @Test
    void readsCachedFeedPageAndInvalidatesProfileFeedKeys() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        OnlineServingCacheService cacheService = new OnlineServingCacheService(redisTemplate, new ObjectMapper());
        UUID profileId = UUID.randomUUID();
        String pageKey = cacheService.feedPageKey(profileId, 10, null);
        when(valueOperations.get(pageKey)).thenReturn("{\"items\":[],\"nextCursor\":null,\"cacheMetadata\":{\"cacheHit\":false}}");

        assertThat(cacheService.get(pageKey, FeedPage.class)).isPresent();

        when(redisTemplate.keys("mgrp:feed:page:" + profileId + ":*")).thenReturn(Set.of(pageKey));
        cacheService.invalidateFeed(profileId);

        verify(redisTemplate).delete(cacheService.activeFeedKey(profileId));
        verify(redisTemplate).delete(pageKey);
    }

    @Test
    void writesAssignmentWithTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        OnlineServingCacheService cacheService = new OnlineServingCacheService(redisTemplate, new ObjectMapper());
        String key = cacheService.assignmentKey(UUID.randomUUID(), "mgrp-test");
        cacheService.putAssignment(key, Map.of("assignedVariantKey", "graph"));

        verify(valueOperations).set(eq(key), any(String.class), any(java.time.Duration.class));
    }
}
