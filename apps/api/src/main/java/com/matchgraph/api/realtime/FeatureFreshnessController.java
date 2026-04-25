package com.matchgraph.api.realtime;

import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.FeatureFreshnessCheck;
import com.matchgraph.api.realtime.RealtimeModels.FeatureFreshnessCheckRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class FeatureFreshnessController {

    private final OnlineFeatureFreshnessGuardService service;

    public FeatureFreshnessController(OnlineFeatureFreshnessGuardService service) {
        this.service = service;
    }

    @PostMapping("/features/freshness/check")
    public FeatureFreshnessCheck check(@RequestBody FeatureFreshnessCheckRequest request) {
        return service.check(request);
    }

    @GetMapping("/features/freshness/checks/{checkId}")
    public FeatureFreshnessCheck get(@PathVariable UUID checkId) {
        return service.get(checkId);
    }
}
