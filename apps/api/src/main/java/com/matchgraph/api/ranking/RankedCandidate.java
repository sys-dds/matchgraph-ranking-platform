package com.matchgraph.api.ranking;

import java.math.BigDecimal;
import java.util.UUID;

import com.matchgraph.api.feed.ItemResponse;

public record RankedCandidate(
    UUID itemId,
    ItemResponse item,
    BigDecimal score,
    ScoreExplanation explanation
) {
}
