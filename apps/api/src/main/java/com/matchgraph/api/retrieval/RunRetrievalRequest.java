package com.matchgraph.api.retrieval;

import java.util.Map;

public record RunRetrievalRequest(
    Integer limit,
    Map<CandidateSourceType, Integer> perSourceBudgets,
    Boolean includeExcluded
) {
}
