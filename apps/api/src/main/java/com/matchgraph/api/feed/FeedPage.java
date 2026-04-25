package com.matchgraph.api.feed;

import java.util.List;
import java.util.Map;

public record FeedPage(
    List<FeedItem> items,
    String nextCursor,
    Map<String, Object> cacheMetadata
) {
    public FeedPage(List<FeedItem> items, String nextCursor) {
        this(items, nextCursor, Map.of());
    }
}
