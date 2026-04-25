package com.matchgraph.api.realtime;

import java.util.List;
import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionEvent;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionRequest;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeInteractionResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RealtimeInteractionController {

    private final RealtimeInteractionService service;

    public RealtimeInteractionController(RealtimeInteractionService service) {
        this.service = service;
    }

    @PostMapping("/realtime/interactions")
    public RealtimeInteractionResponse ingest(@RequestBody RealtimeInteractionRequest request) {
        return service.ingest(request);
    }

    @GetMapping("/realtime/interactions/{eventId}")
    public RealtimeInteractionEvent get(@PathVariable UUID eventId) {
        return service.get(eventId);
    }

    @GetMapping("/profiles/{profileId}/realtime/interactions")
    public List<RealtimeInteractionEvent> list(@PathVariable UUID profileId) {
        return service.list(profileId);
    }
}
