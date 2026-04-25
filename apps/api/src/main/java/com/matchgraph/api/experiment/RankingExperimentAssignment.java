package com.matchgraph.api.experiment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RankingExperimentAssignment(
    UUID id,
    UUID experimentId,
    UUID profileId,
    String experimentKey,
    String assignedVariantKey,
    String assignedRankingVersion,
    boolean holdout,
    String assignmentReason,
    String assignmentHash,
    OffsetDateTime createdAt
) {
}
