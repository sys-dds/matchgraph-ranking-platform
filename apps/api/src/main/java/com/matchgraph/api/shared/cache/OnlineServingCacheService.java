package com.matchgraph.api.shared.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class OnlineServingCacheService {

    private static final Duration ASSIGNMENT_TTL = Duration.ofMinutes(30);
    private static final Duration FEED_TTL = Duration.ofMinutes(5);
    private static final Duration RANKING_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public OnlineServingCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException exception) {
            try {
                redisTemplate.delete(key);
            } catch (RuntimeException deleteException) {
                // Cache is an optimization; corrupt values must not break serving.
            }
            return Optional.empty();
        }
    }

    public void putAssignment(String key, Object value) {
        put(key, value, ASSIGNMENT_TTL);
    }

    public void putFeed(String key, Object value) {
        put(key, value, FEED_TTL);
    }

    public void putRanking(String key, Object value) {
        put(key, value, RANKING_TTL);
    }

    public String assignmentKey(UUID profileId, String experimentKey) {
        return "mgrp:assignment:" + experimentKey + ":" + profileId;
    }

    public String activeFeedKey(UUID profileId) {
        return "mgrp:feed:active:" + profileId;
    }

    public String feedPageKey(UUID profileId, int limit, String cursor) {
        return "mgrp:feed:page:" + profileId + ":limit:" + limit + ":cursor:" + (cursor == null ? "start" : cursor);
    }

    public String rankingDecisionKey(UUID decisionLogId) {
        return "mgrp:ranking:decision:" + decisionLogId;
    }

    public String rankingReplayKey(UUID decisionLogId) {
        return "mgrp:ranking:replay:" + decisionLogId;
    }

    public void invalidateFeed(UUID profileId) {
        try {
            redisTemplate.delete(activeFeedKey(profileId));
            for (String key : redisTemplate.keys("mgrp:feed:page:" + profileId + ":*")) {
                redisTemplate.delete(key);
            }
        } catch (RuntimeException exception) {
            // Cache is an optimization; serving remains correct when Redis is unavailable.
        }
    }

    public void invalidateRanking(UUID decisionLogId) {
        try {
            redisTemplate.delete(rankingDecisionKey(decisionLogId));
            redisTemplate.delete(rankingReplayKey(decisionLogId));
        } catch (RuntimeException exception) {
            // Cache is an optimization; serving remains correct when Redis is unavailable.
        }
    }

    private void put(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("cache value must be JSON serializable", exception);
        } catch (RuntimeException exception) {
            // Cache is an optimization; serving remains correct when Redis is unavailable.
        }
    }
}
