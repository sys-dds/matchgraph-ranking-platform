package com.matchgraph.api.retrieval;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RetrievedCandidate(
    UUID candidateProfileId,
    List<CandidateSourceType> sourceTypes,
    int sourceRank,
    boolean excluded,
    String exclusionReason,
    BigDecimal sourceScore,
    Map<String, Object> sourceReason
) {
    public static RetrievedCandidate sourced(UUID candidateProfileId, CandidateSourceType sourceType, int sourceRank) {
        return sourced(candidateProfileId, sourceType, sourceRank, null, Map.of());
    }

    public static RetrievedCandidate sourced(
        UUID candidateProfileId,
        CandidateSourceType sourceType,
        int sourceRank,
        BigDecimal sourceScore,
        Map<String, Object> sourceReason
    ) {
        return new RetrievedCandidate(candidateProfileId, List.of(sourceType), sourceRank, false, null, sourceScore, sourceReason);
    }
}
