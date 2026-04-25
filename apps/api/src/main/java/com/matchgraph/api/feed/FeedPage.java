package com.matchgraph.api.feed;

import java.util.List;

public record FeedPage(
    List<FeedItem> items,
    String nextCursor
) {
}
