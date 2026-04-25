package com.matchgraph.api.exposure;

import java.math.BigDecimal;

import com.matchgraph.api.ranking.RankingDecisionItem;

public record AdjustedRankingItem(
    RankingDecisionItem item,
    int adjustedPosition,
    BigDecimal adjustedScore,
    BigDecimal boostAmount,
    BigDecimal downrankAmount,
    String reason
) {
}
