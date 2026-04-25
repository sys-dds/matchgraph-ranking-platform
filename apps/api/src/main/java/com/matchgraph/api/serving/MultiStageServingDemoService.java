package com.matchgraph.api.serving;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.MultiStageServingDemoRun;
import com.matchgraph.api.serving.ServingModels.MultiStageServingRequest;
import com.matchgraph.api.serving.ServingModels.MultiStageServingResponse;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class MultiStageServingDemoService {

    private final RecommendationSurfaceRepository repository;
    private final MultiSurfaceRecommendationService recommendationService;

    public MultiStageServingDemoService(RecommendationSurfaceRepository repository, MultiSurfaceRecommendationService recommendationService) {
        this.repository = repository;
        this.recommendationService = recommendationService;
    }

    public MultiStageServingDemoRun run(UUID profileId) {
        if (profileId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is required");
        }
        UUID demoRunId = repository.createDemoRun();
        List<String> scenarios = List.of("normal_serving", "source_timeout_fallback", "model_unavailable_fallback", "fatigue_suppression", "slate_constraint_enforcement", "session_intent_adaptation", "degraded_partial_result");
        for (String scenario : scenarios) {
            MultiStageServingRequest request = switch (scenario) {
                case "source_timeout_fallback" -> new MultiStageServingRequest(null, null, null, true, false, false);
                case "model_unavailable_fallback" -> new MultiStageServingRequest(null, null, "ltr:demo:model", false, true, false);
                case "degraded_partial_result" -> new MultiStageServingRequest(null, 20, null, false, false, true);
                default -> new MultiStageServingRequest(null, null, null, false, false, false);
            };
            MultiStageServingResponse response = recommendationService.multiStage(profileId, "HOME_FEED", request);
            repository.insertDemoStep(demoRunId, scenario, "COMPLETED", response.traceId(), Map.of("degraded", response.degraded(), "warnings", response.warnings()));
        }
        repository.completeDemo(demoRunId, Map.of("scenarioCount", scenarios.size(), "skippedOptional", List.of()));
        return get(demoRunId);
    }

    public MultiStageServingDemoRun get(UUID demoRunId) {
        List<Map<String, Object>> steps = new ArrayList<>(repository.demoSteps(demoRunId));
        return new MultiStageServingDemoRun(demoRunId, "COMPLETED", steps, Map.of("steps", steps.size()));
    }
}
