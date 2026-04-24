package com.matchgraph.api.profile;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ProfileResponse(
    UUID id,
    String externalRef,
    String displayName,
    String profileType,
    String status,
    String bio,
    String city,
    String region,
    String country,
    OffsetDateTime lastActiveAt,
    BigDecimal profileCompletenessScore,
    String embeddingStatus,
    List<ProfileInterestResponse> interests,
    ProfileLocationResponse location,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
