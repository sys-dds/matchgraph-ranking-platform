package com.matchgraph.api.retrieval;

import java.util.List;
import java.util.UUID;

public interface CandidateSource {

    CandidateSourceType sourceType();

    List<RetrievedCandidate> retrieve(UUID profileId, int limit);
}
