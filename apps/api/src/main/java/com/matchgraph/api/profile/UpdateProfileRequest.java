package com.matchgraph.api.profile;

import java.time.OffsetDateTime;

public record UpdateProfileRequest(
    String displayName,
    String status,
    String bio,
    String city,
    String region,
    String country,
    OffsetDateTime lastActiveAt
) {
}
