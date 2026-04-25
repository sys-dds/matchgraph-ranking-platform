package com.matchgraph.api.demo;

import java.util.Map;

public record RankingScienceDemoRequest(
    Long seed,
    Integer profileCount,
    Integer clusterCount,
    Map<String, Object> config
) {
}
