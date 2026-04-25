package com.matchgraph.api.modelquality;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DriftRun(
    UUID id,
    UUID baselineDatasetRunId,
    UUID candidateDatasetRunId,
    String baselineModelVersion,
    String candidateModelVersion,
    String segmentKey,
    String status,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    List<DriftResult> results
) {
}
