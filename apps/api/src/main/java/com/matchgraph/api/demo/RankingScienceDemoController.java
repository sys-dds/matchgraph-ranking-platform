package com.matchgraph.api.demo;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo/ranking-science")
public class RankingScienceDemoController {

    private final RankingScienceDemoService service;

    public RankingScienceDemoController(RankingScienceDemoService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public RankingScienceDemoReport run(@RequestBody(required = false) RankingScienceDemoRequest request) {
        return service.run(request);
    }

    @GetMapping("/runs/{demoRunId}")
    public RankingScienceDemoRun get(@PathVariable UUID demoRunId) {
        return service.get(demoRunId);
    }
}
