package com.matchgraph.api.metrics;

import java.util.List;

public record RankingMetricsSummaryResponse(
    List<RankingMetricsSummaryRow> rows
) {
}
