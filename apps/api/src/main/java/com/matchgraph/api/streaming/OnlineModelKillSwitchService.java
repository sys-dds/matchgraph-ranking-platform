package com.matchgraph.api.streaming;

import java.util.Map;

import com.matchgraph.api.streaming.StreamingModels.ModelKillSwitchState;

import org.springframework.stereotype.Service;

@Service
public class OnlineModelKillSwitchService {

    private final ModelKillSwitchRepository repository;

    public OnlineModelKillSwitchService(ModelKillSwitchRepository repository) {
        this.repository = repository;
    }

    public ModelKillSwitchState kill(String modelKey, String versionKey, String reason) {
        return repository.kill(modelKey, versionKey, reason == null ? "manual kill switch" : reason, true, Map.of("trigger", reason == null ? "MANUAL" : reason, "reversible", true));
    }

    public ModelKillSwitchState restore(String modelKey, String versionKey, boolean emergencyRestore) {
        return repository.restore(modelKey, versionKey, emergencyRestore, Map.of("emergencyRestore", emergencyRestore, "requiresRolloutGateReapproval", !emergencyRestore));
    }

    public ModelKillSwitchState state(String modelKey, String versionKey) {
        return repository.state(modelKey, versionKey);
    }

    public boolean killed(String modelKey, String versionKey) {
        return repository.killed(modelKey, versionKey);
    }
}
