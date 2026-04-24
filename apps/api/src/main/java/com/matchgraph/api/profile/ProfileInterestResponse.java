package com.matchgraph.api.profile;

import java.math.BigDecimal;

public record ProfileInterestResponse(
    String interestKey,
    String interestValue,
    BigDecimal weight
) {
}
