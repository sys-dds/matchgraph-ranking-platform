package com.matchgraph.api.causal;

import java.util.Map;
import java.util.UUID;

public record PropensityBackfillResult(
    UUID datasetRunId,
    int examplesScanned,
    int logsCreated,
    int unknownCount,
    Map<String, Object> summary
) {
}
