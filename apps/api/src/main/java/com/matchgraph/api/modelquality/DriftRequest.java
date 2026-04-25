package com.matchgraph.api.modelquality;

import java.util.UUID;

public record DriftRequest(
    UUID baselineDatasetRunId,
    UUID candidateDatasetRunId,
    String baselineModelVersion,
    String candidateModelVersion,
    String segmentKey
) {
}
