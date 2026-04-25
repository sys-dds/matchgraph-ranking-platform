package com.matchgraph.api.serving;

import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class CandidateSourceHealthService {

    public String health(String sourceKey, boolean simulateTimeout) {
        return simulateTimeout && "VECTOR_SIMILARITY".equals(sourceKey) ? "TIMEOUT_SIMULATED" : "HEALTHY";
    }

    public Map<String, Object> detail(String sourceKey, boolean simulateTimeout) {
        return Map.of("sourceKey", sourceKey, "health", health(sourceKey, simulateTimeout), "qualityScore", "HEALTHY".equals(health(sourceKey, simulateTimeout)) ? 1.0 : 0.0);
    }
}
