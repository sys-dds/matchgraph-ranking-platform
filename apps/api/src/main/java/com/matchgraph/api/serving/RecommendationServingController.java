package com.matchgraph.api.serving;

import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.MultiStageServingDemoRun;
import com.matchgraph.api.serving.ServingModels.MultiStageServingRequest;
import com.matchgraph.api.serving.ServingModels.MultiStageServingResponse;
import com.matchgraph.api.serving.ServingModels.RecommendationSession;
import com.matchgraph.api.serving.ServingModels.RecommendationSurfaceRequest;
import com.matchgraph.api.serving.ServingModels.RecommendationSurfaceResponse;
import com.matchgraph.api.serving.ServingModels.ServingTrace;
import com.matchgraph.api.serving.ServingModels.SessionIntentEvent;
import com.matchgraph.api.serving.ServingModels.SessionIntentState;
import com.matchgraph.api.serving.ServingModels.SurfaceConfig;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RecommendationServingController {

    private final RecommendationSurfaceService surfaceService;
    private final MultiSurfaceRecommendationService recommendationService;
    private final SessionIntentService sessionIntentService;
    private final MultiStageServingTraceService traceService;
    private final MultiStageServingDemoService demoService;

    public RecommendationServingController(
        RecommendationSurfaceService surfaceService,
        MultiSurfaceRecommendationService recommendationService,
        SessionIntentService sessionIntentService,
        MultiStageServingTraceService traceService,
        MultiStageServingDemoService demoService
    ) {
        this.surfaceService = surfaceService;
        this.recommendationService = recommendationService;
        this.sessionIntentService = sessionIntentService;
        this.traceService = traceService;
        this.demoService = demoService;
    }

    @PostMapping("/recommendation-surfaces")
    public SurfaceConfig createSurface(@RequestBody RecommendationSurfaceRequest request) {
        return surfaceService.create(request);
    }

    @GetMapping("/recommendation-surfaces/{surfaceKey}")
    public SurfaceConfig getSurface(@PathVariable String surfaceKey) {
        return surfaceService.get(surfaceKey);
    }

    @PostMapping("/profiles/{profileId}/recommendations/{surfaceKey}")
    public RecommendationSurfaceResponse recommend(@PathVariable UUID profileId, @PathVariable String surfaceKey) {
        return recommendationService.recommend(profileId, surfaceKey);
    }

    @PostMapping("/profiles/{profileId}/recommendations/{surfaceKey}/multi-stage")
    public MultiStageServingResponse multiStage(@PathVariable UUID profileId, @PathVariable String surfaceKey, @RequestBody(required = false) MultiStageServingRequest request) {
        return recommendationService.multiStage(profileId, surfaceKey, request);
    }

    @PostMapping("/profiles/{profileId}/recommendation-sessions")
    public RecommendationSession createSession(@PathVariable UUID profileId) {
        return sessionIntentService.create(profileId);
    }

    @PostMapping("/recommendation-sessions/{sessionId}/events")
    public SessionIntentState event(@PathVariable UUID sessionId, @RequestBody SessionIntentEvent event) {
        return sessionIntentService.record(sessionId, event);
    }

    @GetMapping("/recommendation-sessions/{sessionId}/intent")
    public SessionIntentState intent(@PathVariable UUID sessionId) {
        return sessionIntentService.state(sessionId);
    }

    @GetMapping("/recommendation-serving/traces/{traceId}")
    public ServingTrace trace(@PathVariable UUID traceId) {
        return traceService.get(traceId);
    }

    @PostMapping("/demo/multi-stage-serving/run")
    public MultiStageServingDemoRun runDemo(@RequestParam UUID profileId) {
        return demoService.run(profileId);
    }

    @GetMapping("/demo/multi-stage-serving/runs/{demoRunId}")
    public MultiStageServingDemoRun getDemo(@PathVariable UUID demoRunId) {
        return demoService.get(demoRunId);
    }
}
