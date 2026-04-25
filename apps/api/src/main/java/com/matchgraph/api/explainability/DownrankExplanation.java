package com.matchgraph.api.explainability;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DownrankExplanation(
    UUID decisionLogId,
    UUID candidateProfileId,
    List<String> downrankReasons,
    Map<String, Object> evidence
) {
}
