package com.matchgraph.api.feed;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles/{profileId}/feed/discovery")
public class DiscoveryFeedController {

    private final DiscoveryFeedService discoveryFeedService;

    public DiscoveryFeedController(DiscoveryFeedService discoveryFeedService) {
        this.discoveryFeedService = discoveryFeedService;
    }

    @PostMapping("/refresh")
    public FeedSnapshot refresh(@PathVariable UUID profileId, @RequestBody(required = false) FeedRefreshRequest request) {
        return discoveryFeedService.refresh(profileId, request);
    }

    @GetMapping
    public FeedPage read(
        @PathVariable UUID profileId,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(required = false) String cursor
    ) {
        return discoveryFeedService.read(profileId, limit, cursor);
    }
}
