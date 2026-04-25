package com.matchgraph.api.metrics;

public record RankingMetricsIngestResponse(
    int servedRows,
    int interactionRows,
    int skippedInteractionRows,
    int totalRows
) {
}
