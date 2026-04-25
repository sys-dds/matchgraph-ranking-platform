package com.matchgraph.api.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

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

    @Test
    void corruptJsonDeletesKeyAndFallsBack() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        OnlineServingCacheService cacheService = new OnlineServingCacheService(redisTemplate, new ObjectMapper());
        String key = "bad-json";
        when(valueOperations.get(key)).thenReturn("{not-json");

        assertThat(cacheService.get(key, FeedPage.class)).isEmpty();
        verify(redisTemplate).delete(key);
    }

    @Test
    void redisRuntimeExceptionFallsBack() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis unavailable"));

        OnlineServingCacheService cacheService = new OnlineServingCacheService(redisTemplate, new ObjectMapper());

        assertThat(cacheService.get("any", FeedPage.class)).isEmpty();
    }

    @Test
    void corruptJsonDeleteFailureStillFallsBack() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("bad-json")).thenReturn("{not-json");
        doThrow(new RuntimeException("delete failed")).when(redisTemplate).delete("bad-json");

        OnlineServingCacheService cacheService = new OnlineServingCacheService(redisTemplate, new ObjectMapper());

        assertThat(cacheService.get("bad-json", FeedPage.class)).isEmpty();
    }
}
