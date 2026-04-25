package com.matchgraph.api.exposure;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ExposureWindow(
    UUID id,
    UUID policyId,
    UUID candidateProfileId,
    String windowKey,
    OffsetDateTime windowStart,
    OffsetDateTime windowEnd,
    int exposureCount,
    int exposureCap,
    Map<String, Object> summary
) {
}
