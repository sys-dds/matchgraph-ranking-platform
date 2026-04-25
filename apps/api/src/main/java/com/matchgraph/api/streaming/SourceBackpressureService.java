package com.matchgraph.api.streaming;

import java.time.OffsetDateTime;
import java.util.Map;

import com.matchgraph.api.streaming.StreamingModels.SourceBackpressureAction;

import org.springframework.stereotype.Service;

@Service
public class SourceBackpressureService {

    private final SourceHealthRepository repository;

    public SourceBackpressureService(SourceHealthRepository repository) {
        this.repository = repository;
    }

    public SourceBackpressureAction apply(String sourceKey, String action, int budgetBefore, Integer budgetAfter) {
        int after = budgetAfter == null ? switch (action) {
            case "DISABLE_TEMPORARILY" -> 0;
            case "USE_FALLBACK", "REDUCE_BUDGET" -> Math.max(1, budgetBefore / 2);
            default -> budgetBefore;
        } : budgetAfter;
        return repository.saveAction(sourceKey, action, budgetBefore, after, OffsetDateTime.now().plusMinutes(30), Map.of("reversible", true, "manualOrGuardrailAction", action));
    }

    public SourceBackpressureAction restore(String sourceKey) {
        return repository.saveAction(sourceKey, "RESTORE", 0, 0, OffsetDateTime.now(), Map.of("reversible", true, "restored", true));
    }
}
