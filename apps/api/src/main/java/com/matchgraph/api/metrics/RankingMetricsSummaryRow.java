package com.matchgraph.api.metrics;

public record RankingMetricsSummaryRow(
    String rankingVersion,
    String experimentKey,
    String variant,
    String candidateSource,
    String positionBucket,
    String eventType,
    long servedCount,
    long interactionCount,
    long positiveCount,
    long negativeCount,
    long matchCount,
    long likeCount,
    long passCount,
    long reportCount,
    long blockCount,
    double ctrLikeRate,
    double matchRate
) {
}
