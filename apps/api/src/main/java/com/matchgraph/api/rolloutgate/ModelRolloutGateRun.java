package com.matchgraph.api.rolloutgate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ModelRolloutGateRun(
    UUID id,
    String candidateModelKey,
    String candidateVersionKey,
    String baselineModelKey,
    String baselineVersionKey,
    String status,
    String recommendation,
    Map<String, Object> config,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    List<ModelRolloutGateCheck> checks
) {
}
