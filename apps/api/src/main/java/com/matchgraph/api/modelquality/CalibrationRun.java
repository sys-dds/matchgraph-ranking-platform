package com.matchgraph.api.modelquality;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CalibrationRun(
    UUID id,
    String modelKey,
    String versionKey,
    UUID datasetRunId,
    String status,
    int bucketCount,
    Map<String, Object> summary,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    List<CalibrationBucket> buckets
) {
}
