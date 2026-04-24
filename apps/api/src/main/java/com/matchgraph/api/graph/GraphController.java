package com.matchgraph.api.graph;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles/{profileId}/graph")
public class GraphController {

    private final GraphEdgeService graphEdgeService;

    public GraphController(GraphEdgeService graphEdgeService) {
        this.graphEdgeService = graphEdgeService;
    }

    @PostMapping("/follow")
    public GraphEdgeResponse follow(@PathVariable UUID profileId, @RequestBody GraphActionRequest request) {
        return graphEdgeService.follow(profileId, request);
    }

    @PostMapping("/unfollow")
    public List<GraphEdgeResponse> unfollow(@PathVariable UUID profileId, @RequestBody GraphActionRequest request) {
        return graphEdgeService.unfollow(profileId, request);
    }

    @PostMapping("/block")
    public GraphEdgeResponse block(@PathVariable UUID profileId, @RequestBody GraphActionRequest request) {
        return graphEdgeService.block(profileId, request);
    }

    @PostMapping("/unblock")
    public List<GraphEdgeResponse> unblock(@PathVariable UUID profileId, @RequestBody GraphActionRequest request) {
        return graphEdgeService.unblock(profileId, request);
    }

    @PostMapping("/mute")
    public GraphEdgeResponse mute(@PathVariable UUID profileId, @RequestBody GraphActionRequest request) {
        return graphEdgeService.mute(profileId, request);
    }

    @PostMapping("/unmute")
    public List<GraphEdgeResponse> unmute(@PathVariable UUID profileId, @RequestBody GraphActionRequest request) {
        return graphEdgeService.unmute(profileId, request);
    }

    @PostMapping("/report")
    public GraphEdgeResponse report(@PathVariable UUID profileId, @RequestBody GraphActionRequest request) {
        return graphEdgeService.report(profileId, request);
    }

    @GetMapping("/outgoing")
    public List<GraphEdgeResponse> outgoing(@PathVariable UUID profileId) {
        return graphEdgeService.outgoing(profileId);
    }

    @GetMapping("/exclusions")
    public List<GraphExclusionResponse> exclusions(@PathVariable UUID profileId) {
        return graphEdgeService.exclusions(profileId);
    }
}
