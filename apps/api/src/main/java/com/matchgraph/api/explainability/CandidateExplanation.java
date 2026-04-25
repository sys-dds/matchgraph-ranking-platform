package com.matchgraph.api.explainability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CandidateExplanation(
    UUID requestId,
    UUID resultId,
    UUID profileId,
    UUID candidateProfileId,
    String explanationType,
    String evidenceStatus,
    ExplanationEvidence durableEvidence,
    List<String> reasons,
    Map<String, Object> result,
    OffsetDateTime createdAt
) {
}
