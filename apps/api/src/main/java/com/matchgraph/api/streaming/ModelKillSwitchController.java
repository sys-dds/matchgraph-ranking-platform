package com.matchgraph.api.streaming;

import com.matchgraph.api.streaming.StreamingModels.ModelKillSwitchState;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ltr/models/{modelKey}/versions/{versionKey}/kill-switch")
public class ModelKillSwitchController {

    private final OnlineModelKillSwitchService service;

    public ModelKillSwitchController(OnlineModelKillSwitchService service) {
        this.service = service;
    }

    @PostMapping("/kill")
    public ModelKillSwitchState kill(@PathVariable String modelKey, @PathVariable String versionKey, @RequestParam(required = false) String reason) {
        return service.kill(modelKey, versionKey, reason);
    }

    @PostMapping("/restore")
    public ModelKillSwitchState restore(@PathVariable String modelKey, @PathVariable String versionKey, @RequestParam(defaultValue = "false") boolean emergencyRestore) {
        return service.restore(modelKey, versionKey, emergencyRestore);
    }

    @GetMapping
    public ModelKillSwitchState state(@PathVariable String modelKey, @PathVariable String versionKey) {
        return service.state(modelKey, versionKey);
    }
}
