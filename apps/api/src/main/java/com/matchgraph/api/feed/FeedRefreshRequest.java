package com.matchgraph.api.feed;

import java.util.UUID;

public record FeedRefreshRequest(
    UUID retrievalRunId,
    Integer limit
) {
}
