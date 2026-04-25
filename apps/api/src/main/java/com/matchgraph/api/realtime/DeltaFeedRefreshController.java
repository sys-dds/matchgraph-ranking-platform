package com.matchgraph.api.realtime;

import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.DeltaFeedRefreshRequest;
import com.matchgraph.api.realtime.RealtimeModels.DeltaFeedRefreshRun;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DeltaFeedRefreshController {

    private final DeltaFeedRefreshService service;

    public DeltaFeedRefreshController(DeltaFeedRefreshService service) {
        this.service = service;
    }

    @PostMapping("/profiles/{profileId}/feeds/{feedSnapshotId}/delta-refresh")
    public DeltaFeedRefreshRun refresh(@PathVariable UUID profileId, @PathVariable UUID feedSnapshotId, @RequestBody(required = false) DeltaFeedRefreshRequest request) {
        return service.refresh(profileId, feedSnapshotId, request);
    }
}
