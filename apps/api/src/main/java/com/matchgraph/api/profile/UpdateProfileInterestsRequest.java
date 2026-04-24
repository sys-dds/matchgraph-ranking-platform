package com.matchgraph.api.profile;

import java.util.List;

public record UpdateProfileInterestsRequest(List<ProfileInterestRequest> interests) {
}
