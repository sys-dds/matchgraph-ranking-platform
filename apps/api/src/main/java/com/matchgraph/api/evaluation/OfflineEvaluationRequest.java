package com.matchgraph.api.evaluation;

import java.time.OffsetDateTime;

public record OfflineEvaluationRequest(
    String rankingVersion,
    OffsetDateTime from,
    OffsetDateTime to,
    Integer k,
    String experimentKey
) {
}
