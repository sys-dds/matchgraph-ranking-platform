package com.matchgraph.api.ltr;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LtrModel(
    UUID id,
    String modelKey,
    String name,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<LtrModelVersion> versions
) {
}
