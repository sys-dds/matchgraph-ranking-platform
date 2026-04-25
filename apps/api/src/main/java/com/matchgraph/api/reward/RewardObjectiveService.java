package com.matchgraph.api.reward;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class RewardObjectiveService {

    public RewardScore score(List<LongTermRewardRepository.EventFact> events, boolean hasMatch, boolean includeNeutral) {
        BigDecimal shortTerm = BigDecimal.ZERO;
        BigDecimal longTerm = BigDecimal.ZERO;
        Map<String, Object> components = new LinkedHashMap<>();
        int likes = 0;
        int passes = 0;
        boolean engaged = false;
        for (LongTermRewardRepository.EventFact event : events) {
            switch (event.type()) {
                case "PROFILE_VIEW" -> {
                    shortTerm = shortTerm.add(BigDecimal.valueOf(0.25));
                    engaged = true;
                }
                case "LIKE" -> {
                    shortTerm = shortTerm.add(BigDecimal.ONE);
                    if (++likes > 1) {
                        longTerm = longTerm.add(BigDecimal.valueOf(0.5));
                    }
                    engaged = true;
                }
                case "PASS", "SKIP" -> {
                    shortTerm = shortTerm.subtract(BigDecimal.valueOf(0.25));
                    if (++passes > 1) {
                        longTerm = longTerm.subtract(BigDecimal.valueOf(0.75));
                    }
                }
                case "BLOCK" -> longTerm = longTerm.subtract(BigDecimal.valueOf(2));
                case "REPORT" -> longTerm = longTerm.subtract(BigDecimal.valueOf(2));
                default -> {
                }
            }
        }
        if (hasMatch) {
            longTerm = longTerm.add(BigDecimal.valueOf(2));
            engaged = true;
        }
        if (!engaged && !includeNeutral) {
            longTerm = longTerm.subtract(BigDecimal.valueOf(0.1));
        }
        components.put("PROFILE_VIEW", count(events, "PROFILE_VIEW"));
        components.put("LIKE", count(events, "LIKE"));
        components.put("MATCH_CREATED", hasMatch ? 1 : 0);
        components.put("PASS", count(events, "PASS") + count(events, "SKIP"));
        components.put("BLOCK", count(events, "BLOCK"));
        components.put("REPORT", count(events, "REPORT"));
        components.put("staleSafetyNegativeSignal", "NOT_AVAILABLE");
        components.put("conversationStarted", "NOT_AVAILABLE");
        components.put("noEngagementWithinWindow", !engaged);
        return new RewardScore(shortTerm, longTerm, shortTerm.add(longTerm), components);
    }

    private long count(List<LongTermRewardRepository.EventFact> events, String type) {
        return events.stream().filter(event -> type.equals(event.type())).count();
    }

    public record RewardScore(BigDecimal shortTermReward, BigDecimal longTermReward, BigDecimal finalRewardValue, Map<String, Object> components) {
    }
}
