package com.matchgraph.api.ltr;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LtrTrainingRequest(
    UUID datasetRunId,
    String modelKey,
    String versionKey,
    String algorithm,
    List<String> featureNames,
    Double trainValidationSplit,
    Long randomSeed,
    Map<String, Object> hyperparameters,
    Boolean allowTinyDataset
) {
}
