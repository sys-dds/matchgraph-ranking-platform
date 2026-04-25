package com.matchgraph.api.training;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record TrainingDatasetRun(
    UUID id,
    String datasetKey,
    OffsetDateTime sourceWindowStart,
    OffsetDateTime sourceWindowEnd,
    int labelWindowHours,
    String status,
    Map<String, Object> config,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt
) {
}
