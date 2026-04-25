package com.matchgraph.api.rolloutgate;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelAcceptanceReportService {

    private final ModelRolloutGateRepository repository;

    public ModelAcceptanceReportService(ModelRolloutGateRepository repository) {
        this.repository = repository;
    }

    public ModelAcceptanceReport get(String modelKey, String versionKey) {
        return repository.report(modelKey, versionKey)
            .orElseGet(() -> repository.latestRun(modelKey, versionKey)
                .map(run -> {
                    Map<String, Object> report = humanReadable(run);
                    repository.insertReport(run.id(), modelKey, versionKey, run.recommendation(), report);
                    return repository.report(modelKey, versionKey).orElseThrow();
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "model acceptance report not found")));
    }

    public Map<String, Object> humanReadable(ModelRolloutGateRun run) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("modelKey", run.candidateModelKey());
        report.put("versionKey", run.candidateVersionKey());
        report.put("recommendation", run.recommendation());
        report.put("checks", run.checks());
        report.put("summary", run.summary());
        report.put("approvalSemantics", "APPROVE permits transition to APPROVED only; ACTIVE is never automatic.");
        return report;
    }
}
