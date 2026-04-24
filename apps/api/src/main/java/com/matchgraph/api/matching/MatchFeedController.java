package com.matchgraph.api.matching;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feed")
public class MatchFeedController {

    private final MatchFeedService matchFeedService;

    public MatchFeedController(MatchFeedService matchFeedService) {
        this.matchFeedService = matchFeedService;
    }

    @GetMapping("/{profileId}")
    public RankedFeedResponse feed(@PathVariable UUID profileId, @RequestParam(required = false) Integer limit) {
        return matchFeedService.feed(profileId, limit);
    }
}
