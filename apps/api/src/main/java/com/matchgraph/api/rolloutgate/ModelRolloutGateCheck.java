package com.matchgraph.api.rolloutgate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ModelRolloutGateCheck(
    UUID id,
    UUID gateRunId,
    String checkKey,
    String status,
    boolean required,
    String observedValue,
    String thresholdValue,
    Map<String, Object> detail,
    OffsetDateTime createdAt
) {
}
