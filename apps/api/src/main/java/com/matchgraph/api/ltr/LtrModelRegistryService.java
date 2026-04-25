package com.matchgraph.api.ltr;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LtrModelRegistryService {

    private static final Set<String> STATES = Set.of("DRAFT", "TRAINED", "CANDIDATE", "SHADOW", "APPROVED", "ACTIVE", "REJECTED", "RETIRED");

    private final LtrModelRegistryRepository repository;

    public LtrModelRegistryService(LtrModelRegistryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public LtrModel createModel(CreateLtrModelRequest request) {
        if (request == null || blank(request.modelKey()) || blank(request.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "modelKey and name are required");
        }
        UUID id = repository.createModel(request.modelKey().trim(), request.name().trim());
        return getModel(request.modelKey());
    }

    public LtrModel getModel(String modelKey) {
        return repository.findModel(modelKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LTR model not found"));
    }

    @Transactional
    public LtrModelVersion createVersion(String modelKey, CreateLtrModelVersionRequest request) {
        if (request == null || blank(request.versionKey()) || blank(request.modelType()) || blank(request.featureSchemaVersion())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "versionKey, modelType, and featureSchemaVersion are required");
        }
        LtrModel model = getModel(modelKey);
        UUID versionId = repository.createVersion(model.id(), model.modelKey(), request);
        repository.insertFeatureSchema(
            model.id(),
            model.modelKey(),
            request.featureSchemaVersion(),
            List.of(),
            Map.of("featureSchemaVersion", request.featureSchemaVersion(), "explicit", true)
        );
        return repository.findVersion(versionId).orElseThrow();
    }

    public LtrModelVersion getVersion(String modelKey, String versionKey) {
        return repository.findVersion(modelKey, versionKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LTR model version not found"));
    }

    public LtrModelArtifact getArtifact(String modelKey, String versionKey) {
        getVersion(modelKey, versionKey);
        return repository.artifact(modelKey, versionKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LTR model artifact not found"));
    }

    @Transactional
    public LtrModelVersion transition(String modelKey, String versionKey, TransitionLtrModelVersionRequest request) {
        if (request == null || blank(request.targetStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetStatus is required");
        }
        String target = request.targetStatus().trim();
        if (!STATES.contains(target)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid model version state");
        }
        LtrModelVersion version = getVersion(modelKey, versionKey);
        if (!validTransition(version.status(), target)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid model version transition " + version.status() + " -> " + target);
        }
        if ("ACTIVE".equals(target)) {
            repository.retireActiveVersions(modelKey, version.id());
        }
        repository.updateVersionStatus(version.id(), target);
        repository.insertTransition(
            version.id(),
            version.status(),
            target,
            request.reason(),
            request.metadata() == null ? Map.of() : request.metadata()
        );
        return getVersion(modelKey, versionKey);
    }

    @Transactional
    public void storeArtifact(
        UUID versionId,
        UUID trainingRunId,
        UUID datasetRunId,
        Map<String, Object> weights,
        List<String> featureNames,
        Map<String, Object> normalization,
        Map<String, Object> metadata,
        Map<String, Object> metrics
    ) {
        LtrModelVersion version = repository.findVersion(versionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LTR model version not found"));
        repository.upsertArtifact(version.id(), weights, featureNames, normalization, metadata);
        repository.linkTraining(version.id(), trainingRunId, datasetRunId, metrics);
        repository.insertFeatureSchema(
            version.modelId(),
            version.modelKey(),
            version.featureSchemaVersion(),
            featureNames,
            Map.of("featureNames", featureNames, "featureSchemaVersion", version.featureSchemaVersion())
        );
        if ("DRAFT".equals(version.status())) {
            repository.updateVersionStatus(version.id(), "TRAINED");
            repository.insertTransition(version.id(), "DRAFT", "TRAINED", "local training completed", Map.of("trainingRunId", trainingRunId.toString()));
        }
    }

    private boolean validTransition(String from, String to) {
        if (!"ACTIVE".equals(from) && "REJECTED".equals(to)) {
            return true;
        }
        return switch (from) {
            case "DRAFT" -> "TRAINED".equals(to);
            case "TRAINED" -> "CANDIDATE".equals(to);
            case "CANDIDATE" -> "SHADOW".equals(to);
            case "SHADOW" -> "APPROVED".equals(to);
            case "APPROVED" -> "ACTIVE".equals(to);
            case "ACTIVE" -> "RETIRED".equals(to);
            default -> false;
        };
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
