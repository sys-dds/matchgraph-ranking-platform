package com.matchgraph.api.retrieval;

import java.util.List;
import java.util.UUID;

public record RetrievedCandidate(
    UUID candidateProfileId,
    List<CandidateSourceType> sourceTypes,
    int sourceRank,
    boolean excluded,
    String exclusionReason
) {
    public static RetrievedCandidate sourced(UUID candidateProfileId, CandidateSourceType sourceType, int sourceRank) {
        return new RetrievedCandidate(candidateProfileId, List.of(sourceType), sourceRank, false, null);
    }
}
