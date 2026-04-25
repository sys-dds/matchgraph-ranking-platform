package com.matchgraph.api.ranking;

import java.math.BigDecimal;
import java.util.Map;

public record RankingPolicy(
    Map<String, BigDecimal> signals,
    Map<String, Object> diversity
) {
}
