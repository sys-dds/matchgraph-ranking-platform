package com.matchgraph.api.profile;

import java.math.BigDecimal;

public record UpdateProfileLocationRequest(
    BigDecimal latitude,
    BigDecimal longitude,
    BigDecimal precisionKm,
    String city,
    String region,
    String country
) {
}
