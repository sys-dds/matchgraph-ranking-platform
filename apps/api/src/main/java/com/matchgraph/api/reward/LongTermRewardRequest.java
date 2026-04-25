package com.matchgraph.api.reward;

import java.util.UUID;

public record LongTermRewardRequest(
    UUID datasetRunId,
    UUID decisionLogId,
    Integer delayedWindowHours,
    Boolean includeNeutral,
    Boolean updateTrainingLabels
) {
}
