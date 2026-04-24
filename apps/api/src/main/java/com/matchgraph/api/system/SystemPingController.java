package com.matchgraph.api.system;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemPingController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of(
            "service", "matchgraph-api",
            "status", "ok"
        );
    }
}
