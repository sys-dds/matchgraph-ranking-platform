package com.matchgraph.api.realtime;

import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.LiveSessionIntentSnapshot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LiveSessionIntentController {

    private final LiveSessionIntentService service;

    public LiveSessionIntentController(LiveSessionIntentService service) {
        this.service = service;
    }

    @PostMapping("/recommendation-sessions/{sessionId}/intent/recompute")
    public LiveSessionIntentSnapshot recompute(@PathVariable UUID sessionId) {
        return service.recompute(sessionId);
    }

    @GetMapping("/recommendation-sessions/{sessionId}/intent/live")
    public LiveSessionIntentSnapshot live(@PathVariable UUID sessionId) {
        return service.latest(sessionId);
    }
}
