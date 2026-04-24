package com.matchgraph.api.retrieval;

import java.util.List;
import java.util.Map;

public record CandidatePool(
    List<RetrievedCandidate> candidates,
    Map<CandidateSourceType, Integer> sourceCoverage,
    int exclusionCount
) {
}
