package com.matchgraph.api.feed;

public record CreateItemRequest(
    String externalRef,
    String title,
    String itemType,
    String status
) {
}
