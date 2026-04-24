package com.matchgraph.api.retrieval;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CandidateRetrievalRun(
    UUID id,
    UUID profileId,
    String status,
    int requestedLimit,
    int finalCandidateCount,
    int exclusionCount,
    Map<CandidateSourceType, Integer> sourceCoverage,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    List<RetrievedCandidate> candidates
) {
}
