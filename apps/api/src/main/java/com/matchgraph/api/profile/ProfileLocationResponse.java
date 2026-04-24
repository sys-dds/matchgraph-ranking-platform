package com.matchgraph.api.profile;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ProfileLocationResponse(
    String city,
    String region,
    String country,
    BigDecimal precisionKm,
    OffsetDateTime updatedAt
) {
}
