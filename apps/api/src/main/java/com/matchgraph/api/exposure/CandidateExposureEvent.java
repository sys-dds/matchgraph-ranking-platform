package com.matchgraph.api.exposure;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CandidateExposureEvent(
    UUID id,
    UUID candidateProfileId,
    UUID viewerProfileId,
    UUID feedSnapshotId,
    UUID feedItemId,
    UUID decisionLogId,
    String rankingVersion,
    String experimentKey,
    String assignedVariantKey,
    String exposureType,
    int position,
    String contextKey,
    OffsetDateTime exposureTimestamp
) {
}
