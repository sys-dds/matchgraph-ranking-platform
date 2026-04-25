package com.matchgraph.api.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.LiveSessionIntentSnapshot;

import org.junit.jupiter.api.Test;

class LiveSessionIntentServiceTest {

    @Test
    void recomputeAppliesDecayConfidenceAndPositiveNegativeWeights() {
        LiveSessionIntentRepository repository = mock(LiveSessionIntentRepository.class);
        LiveSessionIntentService service = new LiveSessionIntentService(repository);
        UUID sessionId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(repository.profileForSession(sessionId)).thenReturn(profileId);
        when(repository.realtimeEvents(sessionId)).thenReturn(List.of(
            Map.of("profileId", profileId, "eventType", "LIKE", "sourceKey", "GRAPH_MUTUALS", "occurredAt", OffsetDateTime.now()),
            Map.of("profileId", profileId, "eventType", "PASS", "sourceKey", "VECTOR_SIMILARITY", "occurredAt", OffsetDateTime.now().minusMinutes(10)),
            Map.of("profileId", profileId, "eventType", "REPORT", "sourceKey", "LOCATION_NEARBY", "occurredAt", OffsetDateTime.now())
        ));
        when(repository.save(eq(sessionId), eq(profileId), anyMap(), anyMap(), anyMap(), any(), any(), anyMap(), any()))
            .thenAnswer(invocation -> new LiveSessionIntentSnapshot(
                UUID.randomUUID(),
                sessionId,
                profileId,
                invocation.getArgument(2),
                invocation.getArgument(3),
                invocation.getArgument(4),
                invocation.getArgument(5),
                invocation.getArgument(6),
                invocation.getArgument(7),
                invocation.getArgument(8)
            ));

        LiveSessionIntentSnapshot snapshot = service.recompute(sessionId);

        assertThat(snapshot.sourceWeights().get("GRAPH_MUTUALS")).isGreaterThan(BigDecimal.ZERO);
        assertThat(snapshot.sourceWeights().get("VECTOR_SIMILARITY")).isNegative();
        assertThat(snapshot.sourceWeights().get("LOCATION_NEARBY")).isLessThan(new BigDecimal("-1.5"));
        assertThat(snapshot.positiveWeights()).containsKey("GRAPH_MUTUALS");
        assertThat(snapshot.negativeWeights()).containsKeys("VECTOR_SIMILARITY", "LOCATION_NEARBY");
        assertThat(snapshot.confidenceScore()).isEqualByComparingTo("0.600000");
        assertThat(snapshot.decayFactor()).isEqualByComparingTo("0.85");
    }
}
