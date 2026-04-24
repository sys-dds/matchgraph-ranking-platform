package com.matchgraph.api.matching;

import java.util.List;
import java.util.UUID;

import com.matchgraph.api.ranking.RankedCandidate;

public record RankedFeedResponse(
    UUID profileId,
    int limit,
    List<RankedCandidate> candidates
) {
}
