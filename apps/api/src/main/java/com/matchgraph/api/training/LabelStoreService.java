package com.matchgraph.api.training;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class LabelStoreService {

    public LabelOutcome resolve(
        List<TrainingDatasetRepository.EventFact> events,
        TrainingDatasetRepository.SyntheticLabelFact syntheticLabel,
        boolean includeSyntheticAsPrimary,
        int labelWindowHours
    ) {
        List<TrainingDatasetRepository.EventFact> ordered = new ArrayList<>(events == null ? List.of() : events);
        ordered.sort(Comparator.comparing(TrainingDatasetRepository.EventFact::eventTime));
        Map<String, Object> components = new LinkedHashMap<>();
        List<LabelComponent> labels = new ArrayList<>();

        for (TrainingDatasetRepository.EventFact event : ordered) {
            BigDecimal value = eventValue(event.eventType());
            if (value != null) {
                labels.add(new LabelComponent(event.eventType(), value, event.eventId(), event.eventTime(), "INTERACTION"));
                components.put(event.eventType(), value);
            }
        }

        if (syntheticLabel != null && (includeSyntheticAsPrimary || labels.isEmpty())) {
            BigDecimal syntheticValue = switch (syntheticLabel.compatibilityLabel()) {
                case "POSITIVE" -> BigDecimal.ONE;
                case "NEGATIVE" -> BigDecimal.ONE.negate();
                default -> BigDecimal.ZERO;
            };
            labels.add(new LabelComponent("SYNTHETIC_" + syntheticLabel.compatibilityLabel(), syntheticValue, null, null, "SYNTHETIC"));
            components.put("syntheticCompatibilityLabel", syntheticLabel.compatibilityLabel());
            components.put("syntheticExpectedRelevance", syntheticLabel.expectedRelevance());
        }

        BigDecimal finalValue = labels.stream()
            .map(LabelComponent::value)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (has(labels, "BLOCK") || has(labels, "REPORT")) {
            finalValue = BigDecimal.valueOf(-2);
            components.put("conflictRule", "strong negative BLOCK/REPORT overrides positive labels");
        } else if (has(labels, "MATCH_CREATED")) {
            finalValue = BigDecimal.valueOf(2);
            components.put("conflictRule", "MATCH_CREATED overrides earlier LIKE");
        }

        boolean hasLabel = !labels.isEmpty();
        boolean positive = hasLabel && finalValue.signum() > 0;
        boolean negative = hasLabel && finalValue.signum() < 0;
        boolean neutral = !hasLabel || finalValue.signum() == 0;
        components.put("labelWindowHours", labelWindowHours);
        components.put("components", labels.stream().map(LabelComponent::asMap).toList());
        components.put("finalLabelValue", finalValue);
        components.put("labelSemantics", "durable interactions and matches first; synthetic labels are fallback unless configured primary");

        return new LabelOutcome(finalValue, positive, negative, neutral, components, labels);
    }

    private boolean has(List<LabelComponent> labels, String type) {
        return labels.stream().anyMatch(label -> type.equals(label.type()));
    }

    private BigDecimal eventValue(String eventType) {
        return switch (eventType) {
            case "PROFILE_VIEW" -> BigDecimal.valueOf(0.25);
            case "LIKE" -> BigDecimal.ONE;
            case "MATCH_CREATED" -> BigDecimal.valueOf(2);
            case "PASS", "SKIP" -> BigDecimal.valueOf(-0.25);
            case "BLOCK", "REPORT" -> BigDecimal.valueOf(-2);
            default -> null;
        };
    }

    public record LabelOutcome(
        BigDecimal value,
        boolean positive,
        boolean negative,
        boolean neutral,
        Map<String, Object> labelJson,
        List<LabelComponent> components
    ) {
    }

    public record LabelComponent(String type, BigDecimal value, UUID eventId, OffsetDateTime eventTime, String source) {
        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            map.put("value", value);
            map.put("eventId", eventId == null ? null : eventId.toString());
            map.put("eventTime", eventTime == null ? null : eventTime.toString());
            map.put("source", source);
            return map;
        }
    }
}
