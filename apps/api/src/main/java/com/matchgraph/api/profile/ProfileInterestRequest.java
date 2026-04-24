package com.matchgraph.api.profile;

import java.math.BigDecimal;

public record ProfileInterestRequest(
    String interestKey,
    String interestValue,
    BigDecimal weight
) {
}
