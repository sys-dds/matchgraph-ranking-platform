package com.matchgraph.api.interleaving;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record InterleavingExperiment(
    UUID id,
    String experimentKey,
    String name,
    String status,
    String rankerAVersion,
    String rankerBVersion,
    String method,
    Map<String, Object> config,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
