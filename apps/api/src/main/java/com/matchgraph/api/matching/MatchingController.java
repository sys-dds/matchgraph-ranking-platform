package com.matchgraph.api.matching;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles/{profileId}")
public class MatchingController {

    private final MatchingService matchingService;

    public MatchingController(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    @PostMapping("/swipes")
    public SwipeResponse swipe(@PathVariable UUID profileId, @RequestBody SwipeRequest request) {
        return matchingService.swipe(profileId, request);
    }

    @GetMapping("/matches")
    public List<MatchResponse> matches(@PathVariable UUID profileId) {
        return matchingService.matches(profileId);
    }
}
