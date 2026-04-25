package com.matchgraph.api.ranking;

import java.math.BigDecimal;

public record RankingReason(
    String reasonKey,
    BigDecimal scoreDelta,
    String explanation
) {
}
