package com.matchgraph.api.training;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TrainingLabel(
    UUID id,
    UUID trainingExampleId,
    String labelType,
    BigDecimal labelValue,
    UUID eventId,
    OffsetDateTime eventTime,
    int labelWindowHours,
    String source,
    OffsetDateTime createdAt
) {
}
