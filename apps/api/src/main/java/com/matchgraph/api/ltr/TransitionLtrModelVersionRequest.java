package com.matchgraph.api.ltr;

import java.util.Map;

public record TransitionLtrModelVersionRequest(String targetStatus, String reason, Map<String, Object> metadata) {
}
