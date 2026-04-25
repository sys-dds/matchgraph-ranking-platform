package com.matchgraph.api.realtime;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.SessionIntentState;

import org.springframework.stereotype.Service;

@Service
public class AdaptiveSourceRoutingService {

    private final RealtimeInteractionRepository repository;
    private final SourceFeedbackService feedbackService;

    public AdaptiveSourceRoutingService(RealtimeInteractionRepository repository, SourceFeedbackService feedbackService) {
        this.repository = repository;
        this.feedbackService = feedbackService;
    }

    public Map<String, Integer> adapt(UUID profileId, UUID sessionId, Map<String, Integer> baseBudgets, SessionIntentState intent) {
        Map<String, Integer> adjusted = new LinkedHashMap<>();
        Map<String, Object> nearlinePreference = repository.sourcePreference(profileId);
        for (Map.Entry<String, Integer> entry : baseBudgets.entrySet()) {
            String source = entry.getKey();
            int before = entry.getValue();
            BigDecimal liveWeight = intent == null ? BigDecimal.ZERO : intent.sourceWeights().getOrDefault(source, BigDecimal.ZERO);
            BigDecimal feedback = feedbackService.recentSignal(profileId, sessionId, source);
            BigDecimal nearline = preference(nearlinePreference.get(source));
            int delta = boundedDelta(liveWeight.add(feedback).add(nearline));
            int min = before <= 1 ? 1 : 2;
            int after = Math.max(min, Math.min(before + 3, before + delta));
            adjusted.put(source, after);
            repository.insertSourceBudgetSnapshot(profileId, sessionId, source, before, after, Map.of(
                "liveWeight", liveWeight,
                "recentSourceFeedback", feedback,
                "nearlinePreference", nearline,
                "bounded", true,
                "safetyStillWins", true
            ));
        }
        return adjusted;
    }

    private int boundedDelta(BigDecimal signal) {
        if (signal.compareTo(new BigDecimal("1.0")) >= 0) {
            return 2;
        }
        if (signal.compareTo(new BigDecimal("0.25")) >= 0) {
            return 1;
        }
        if (signal.compareTo(new BigDecimal("-1.0")) <= 0) {
            return -2;
        }
        if (signal.compareTo(new BigDecimal("-0.25")) <= 0) {
            return -1;
        }
        return 0;
    }

    private BigDecimal preference(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO;
    }
}
