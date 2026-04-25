package com.matchgraph.api.streaming;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.streaming.StreamingModels.CacheInvalidationNode;
import com.matchgraph.api.streaming.StreamingModels.CacheInvalidationRun;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cache-invalidation")
public class CacheInvalidationController {

    private final CacheInvalidationGraphService service;

    public CacheInvalidationController(CacheInvalidationGraphService service) {
        this.service = service;
    }

    @PostMapping("/graph/build")
    public Map<String, Object> build() {
        return service.build();
    }

    @PostMapping("/runs")
    public CacheInvalidationRun run(@RequestParam String nodeType, @RequestParam String nodeRef, @RequestParam(defaultValue = "false") boolean global) {
        return service.invalidate(nodeType, nodeRef, global);
    }

    @GetMapping("/runs/{runId}")
    public CacheInvalidationRun read(@PathVariable UUID runId) {
        return service.run(runId);
    }

    @GetMapping("/affected")
    public List<CacheInvalidationNode> affected(@RequestParam String nodeType, @RequestParam String nodeRef) {
        return service.affected(nodeType, nodeRef);
    }
}
