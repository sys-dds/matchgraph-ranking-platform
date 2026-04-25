package com.matchgraph.api.training;

import java.time.OffsetDateTime;
import java.util.Map;

public record CreateTrainingDatasetRequest(
    String datasetKey,
    OffsetDateTime sourceWindowStart,
    OffsetDateTime sourceWindowEnd,
    Integer labelWindowHours,
    Boolean includeNeutral,
    Boolean includeSyntheticAsPrimary,
    Integer maxExamples,
    Map<String, Object> config
) {
}
