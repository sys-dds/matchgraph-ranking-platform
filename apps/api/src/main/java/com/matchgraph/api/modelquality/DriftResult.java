package com.matchgraph.api.modelquality;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record DriftResult(
    UUID id,
    UUID runId,
    String resultKey,
    String metricType,
    BigDecimal psiApprox,
    BigDecimal jsApprox,
    String status,
    Map<String, Object> detail,
    OffsetDateTime createdAt
) {
}
