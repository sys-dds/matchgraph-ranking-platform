package com.matchgraph.api.explainability;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record HiddenCandidateExplanation(
    UUID profileId,
    UUID candidateProfileId,
    List<String> hiddenReasons,
    Map<String, Object> evidence
) {
}
