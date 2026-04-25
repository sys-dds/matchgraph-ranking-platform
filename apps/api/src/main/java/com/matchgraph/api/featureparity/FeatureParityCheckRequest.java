package com.matchgraph.api.featureparity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FeatureParityCheckRequest(
    UUID datasetRunId,
    UUID decisionLogId,
    Map<String, Object> toleranceConfig,
    List<String> featureNames,
    Integer maxExamples
) {
}
