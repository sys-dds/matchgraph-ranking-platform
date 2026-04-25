package com.matchgraph.api.rolloutgate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ModelAcceptanceReport(
    UUID id,
    UUID gateRunId,
    String modelKey,
    String versionKey,
    String recommendation,
    Map<String, Object> report,
    OffsetDateTime createdAt
) {
}
