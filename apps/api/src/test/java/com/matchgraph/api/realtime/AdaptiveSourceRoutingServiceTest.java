package com.matchgraph.api.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.SessionIntentState;

import org.junit.jupiter.api.Test;

/**
 * Focused unit test for bounded budget adaptation math; Spring/Testcontainers
 * coverage lives in the MGRP-047-054 integration test.
 */
class AdaptiveSourceRoutingServiceTest {

    @Test
    void adaptiveRoutingBoundsPositiveAndNegativeBudgetChanges() {
        RealtimeInteractionRepository repository = mock(RealtimeInteractionRepository.class);
        SourceFeedbackService feedbackService = mock(SourceFeedbackService.class);
        AdaptiveSourceRoutingService service = new AdaptiveSourceRoutingService(repository, feedbackService);
        UUID profileId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Map<String, Integer> base = new LinkedHashMap<>();
        base.put("GRAPH_MUTUALS", 3);
        base.put("VECTOR_SIMILARITY", 3);
        when(repository.sourcePreference(profileId)).thenReturn(Map.of("GRAPH_MUTUALS", BigDecimal.ONE, "VECTOR_SIMILARITY", BigDecimal.valueOf(-1)));
        when(feedbackService.recentSignal(profileId, sessionId, "GRAPH_MUTUALS")).thenReturn(BigDecimal.ONE);
        when(feedbackService.recentSignal(profileId, sessionId, "VECTOR_SIMILARITY")).thenReturn(BigDecimal.valueOf(-1));
        SessionIntentState intent = new SessionIntentState(
            sessionId,
            profileId,
            Map.of("GRAPH_MUTUALS", BigDecimal.ONE, "VECTOR_SIMILARITY", BigDecimal.valueOf(-1)),
            Map.of(),
            OffsetDateTime.now().plusMinutes(30)
        );

        Map<String, Integer> adjusted = service.adapt(profileId, sessionId, base, intent);

        assertThat(adjusted.get("GRAPH_MUTUALS")).isEqualTo(5);
        assertThat(adjusted.get("VECTOR_SIMILARITY")).isEqualTo(2);
        verify(repository).insertSourceBudgetSnapshot(eq(profileId), eq(sessionId), eq("GRAPH_MUTUALS"), eq(3), eq(5), anyMap());
        verify(repository).insertSourceBudgetSnapshot(eq(profileId), eq(sessionId), eq("VECTOR_SIMILARITY"), anyInt(), anyInt(), anyMap());
    }
}
