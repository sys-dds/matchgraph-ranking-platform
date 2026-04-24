package com.matchgraph.api.profile;

public record CreateProfileRequest(
    String externalRef,
    String displayName,
    String profileType,
    String status
) {
}
