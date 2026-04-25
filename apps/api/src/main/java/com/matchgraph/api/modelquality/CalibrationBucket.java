package com.matchgraph.api.modelquality;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CalibrationBucket(
    UUID id,
    UUID runId,
    int bucketIndex,
    BigDecimal bucketStart,
    BigDecimal bucketEnd,
    int exampleCount,
    BigDecimal predictedAverage,
    BigDecimal observedRewardAverage,
    BigDecimal observedPositiveRate,
    BigDecimal calibrationError,
    String confidenceStatus,
    OffsetDateTime createdAt
) {
}
