package com.matchgraph.api.realtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.LiveSessionIntentSnapshot;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveSessionIntentService {

    private static final BigDecimal DECAY_FACTOR = new BigDecimal("0.85");

    private final LiveSessionIntentRepository repository;

    public LiveSessionIntentService(LiveSessionIntentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public LiveSessionIntentSnapshot recompute(UUID sessionId) {
        UUID profileId = repository.profileForSession(sessionId);
        Map<String, BigDecimal> sourceWeights = new LinkedHashMap<>();
        Map<String, BigDecimal> positiveWeights = new LinkedHashMap<>();
        Map<String, BigDecimal> negativeWeights = new LinkedHashMap<>();
        OffsetDateTime now = OffsetDateTime.now();
        int eventCount = 0;
        for (Map<String, Object> event : repository.realtimeEvents(sessionId)) {
            String source = String.valueOf(event.get("sourceKey"));
            if (source.isBlank()) {
                continue;
            }
            eventCount++;
            String type = String.valueOf(event.get("eventType"));
            OffsetDateTime occurredAt = (OffsetDateTime) event.get("occurredAt");
            long fiveMinuteBuckets = Math.max(0, Duration.between(occurredAt, now).toMinutes() / 5);
            BigDecimal decayed = DECAY_FACTOR.pow((int) Math.min(20, fiveMinuteBuckets));
            BigDecimal delta = switch (type) {
                case "LIKE", "SOURCE_POSITIVE", "MATCH_CREATED" -> BigDecimal.ONE;
                case "PROFILE_VIEW" -> new BigDecimal("0.25");
                case "PASS", "SOURCE_NEGATIVE" -> new BigDecimal("-0.75");
                case "BLOCK", "REPORT" -> new BigDecimal("-2.00");
                default -> BigDecimal.ZERO;
            };
            BigDecimal weighted = delta.multiply(decayed).setScale(6, RoundingMode.HALF_UP);
            sourceWeights.merge(source, weighted, BigDecimal::add);
            if (weighted.signum() >= 0) {
                positiveWeights.merge(source, weighted, BigDecimal::add);
            } else {
                negativeWeights.merge(source, weighted.abs(), BigDecimal::add);
            }
        }
        BigDecimal confidence = BigDecimal.valueOf(Math.min(1.0, eventCount / 5.0)).setScale(6, RoundingMode.HALF_UP);
        if (eventCount == 0) {
            confidence = BigDecimal.ZERO;
        }
        Map<String, Object> explanation = Map.of(
            "eventCount", eventCount,
            "decay", "0.85 per 5 minutes",
            "semantics", "short-lived explainable live source weights; hard exclusions and invalidations still win"
        );
        return repository.save(sessionId, profileId, sourceWeights, positiveWeights, negativeWeights, confidence, DECAY_FACTOR, explanation, now.plusMinutes(60));
    }

    public LiveSessionIntentSnapshot latest(UUID sessionId) {
        return repository.latest(sessionId).orElseGet(() -> recompute(sessionId));
    }
}
