package com.matchgraph.api.rolloutgate;

import java.util.Map;

public record ModelRolloutGateRequest(
    String candidateModelKey,
    String candidateVersionKey,
    String baselineModelKey,
    String baselineVersionKey,
    Map<String, Object> config
) {
}
