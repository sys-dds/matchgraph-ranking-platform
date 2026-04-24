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
    int rawCandidateCount,
    int dedupedCandidateCount,
    int finalCandidateCount,
    int exclusionCount,
    Map<String, Integer> exclusionCounts,
    Map<CandidateSourceType, Integer> sourceCoverage,
    Map<CandidateSourceType, Integer> sourceBudgets,
    Map<String, Object> retrievalQuality,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt,
    List<RetrievedCandidate> candidates
) {
}
