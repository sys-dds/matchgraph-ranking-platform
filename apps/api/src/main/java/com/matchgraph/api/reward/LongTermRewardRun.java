package com.matchgraph.api.reward;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record LongTermRewardRun(
    UUID id,
    UUID datasetRunId,
    UUID decisionLogId,
    int delayedWindowHours,
    boolean includeNeutral,
    boolean updateTrainingLabels,
    String status,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    LongTermRewardResult result
) {
}
