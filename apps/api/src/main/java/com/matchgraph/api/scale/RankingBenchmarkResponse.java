package com.matchgraph.api.scale;

import java.util.List;

public record RankingBenchmarkResponse(
    RankingBenchmarkRun run,
    List<RankingBenchmarkResult> results
) {
}
