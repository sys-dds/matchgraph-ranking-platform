package com.matchgraph.api.interleaving;

import java.util.Map;

public record InterleavingExperimentRequest(
    String experimentKey,
    String name,
    String status,
    String rankerAVersion,
    String rankerBVersion,
    Map<String, Object> config
) {
}
