package com.matchgraph.api.modelquality;

import java.util.UUID;

public record CalibrationRequest(String modelKey, String versionKey, UUID datasetRunId, Integer bucketCount) {
}
