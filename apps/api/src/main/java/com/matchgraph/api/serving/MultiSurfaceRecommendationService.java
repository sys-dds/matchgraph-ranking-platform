package com.matchgraph.api.serving;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.serving.ServingModels.HeavyRankRun;
import com.matchgraph.api.serving.ServingModels.MultiStageServingRequest;
import com.matchgraph.api.serving.ServingModels.MultiStageServingResponse;
import com.matchgraph.api.serving.ServingModels.PreRankRun;
import com.matchgraph.api.serving.ServingModels.RecommendationSurfaceResponse;
import com.matchgraph.api.serving.ServingModels.ServedItem;
import com.matchgraph.api.serving.ServingModels.SlateOptimizationRun;
import com.matchgraph.api.serving.ServingModels.SourceCallResult;
import com.matchgraph.api.serving.ServingModels.SourceRoutingResult;
import com.matchgraph.api.serving.ServingModels.SurfaceConfig;
import com.matchgraph.api.serving.ServingModels.SessionIntentState;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MultiSurfaceRecommendationService {

    private final RecommendationSurfaceService surfaceService;
    private final CandidateSourceRouter sourceRouter;
    private final PreRankService preRankService;
    private final HeavyRankService heavyRankService;
    private final SlateOptimizerService slateOptimizerService;
    private final ServingDegradationService degradationService;
    private final ServingQualityService qualityService;
    private final RecommendationSurfaceRepository repository;
    private final ProfileService profileService;
    private final SessionIntentService sessionIntentService;
    private final FeedFatigueService fatigueService;

    public MultiSurfaceRecommendationService(
        RecommendationSurfaceService surfaceService,
        CandidateSourceRouter sourceRouter,
        PreRankService preRankService,
        HeavyRankService heavyRankService,
        SlateOptimizerService slateOptimizerService,
        ServingDegradationService degradationService,
        ServingQualityService qualityService,
        RecommendationSurfaceRepository repository,
        ProfileService profileService,
        SessionIntentService sessionIntentService,
        FeedFatigueService fatigueService
    ) {
        this.surfaceService = surfaceService;
        this.sourceRouter = sourceRouter;
        this.preRankService = preRankService;
        this.heavyRankService = heavyRankService;
        this.slateOptimizerService = slateOptimizerService;
        this.degradationService = degradationService;
        this.qualityService = qualityService;
        this.repository = repository;
        this.profileService = profileService;
        this.sessionIntentService = sessionIntentService;
        this.fatigueService = fatigueService;
    }

    public RecommendationSurfaceResponse recommend(UUID profileId, String surfaceKey) {
        MultiStageServingResponse response = multiStage(profileId, surfaceKey, new MultiStageServingRequest(null, null, null, false, false, false));
        return new RecommendationSurfaceResponse(response.surfaceKey(), response.servedItems(), response.degraded(), response.traceId(), response.warnings());
    }

    @Transactional
    public MultiStageServingResponse multiStage(UUID profileId, String surfaceKey, MultiStageServingRequest request) {
        profileService.requireExists(profileId);
        SurfaceConfig surface = surfaceService.get(surfaceKey);
        if (!"ENABLED".equals(surface.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "recommendation surface is disabled");
        }
        MultiStageServingRequest effective = request == null ? new MultiStageServingRequest(null, null, null, false, false, false) : request;
        int resultSize = effective.limit() == null ? surface.resultSize() : Math.min(surface.resultSize(), Math.max(1, effective.limit()));
        String rankingVersion = effective.rankingVersion() == null ? surface.rankingVersion() : effective.rankingVersion();
        boolean sourceTimeout = Boolean.TRUE.equals(effective.simulateSourceTimeout());
        boolean modelUnavailable = Boolean.TRUE.equals(effective.simulateModelUnavailable());
        boolean partialRequested = Boolean.TRUE.equals(effective.simulatePartialResult());

        UUID requestId = UUID.randomUUID();
        SessionIntentState intent = effective.sessionId() == null ? null : sessionIntentService.state(effective.sessionId());
        SourceRoutingResult routing = sourceRouter.route(requestId, profileId, surface, effective.sessionId(), intent, sourceTimeout);
        List<SourceCallResult> sourceResults = routing.sourceResults();
        PreRankRun preRank = preRankService.preRank(requestId, profileId, sourceResults, intent, Math.max(resultSize * 2, resultSize));
        HeavyRankRun heavyRank = heavyRankService.rank(requestId, rankingVersion, preRank.survivors(), modelUnavailable, false);
        SlateOptimizationRun slate = slateOptimizerService.optimize(requestId, heavyRank.ranked(), resultSize, partialRequested);
        List<String> warnings = degradationService.warnings(sourceTimeout, heavyRank.fallbackUsed(), slate.partialResult(), slate.selected().size());
        boolean degraded = !warnings.isEmpty();
        Map<String, Object> trace = trace(surface, effective.sessionId(), routing.planId(), routing.budgetReasons(), sourceResults, preRank, heavyRank, slate, warnings, intent);
        repository.createServingRequestWithId(requestId, profileId, surfaceKey, effective.sessionId(), degraded, slate.selected().size(), trace, Map.of("servedItems", slate.selected()));
        repository.insertTraceStep(requestId, "source_routing", "COMPLETED", sourceTimeout ? 200 : 20, Map.of("sources", sourceResults));
        repository.insertTraceStep(requestId, "pre_rank", "COMPLETED", 5, preRank.summary());
        repository.insertTraceStep(requestId, "heavy_rank", heavyRank.fallbackUsed() ? "DEGRADED" : "COMPLETED", heavyRank.durationMs(), Map.of("modelBacked", heavyRank.modelBacked(), "fallbackUsed", heavyRank.fallbackUsed(), "fallbackReason", heavyRank.fallbackReason() == null ? "" : heavyRank.fallbackReason()));
        repository.insertTraceStep(requestId, "slate_optimize", slate.partialResult() ? "PARTIAL" : "COMPLETED", 5, Map.of("partial", slate.partialResult(), "warning", slate.warning() == null ? "" : slate.warning()));
        for (ServedItem item : slate.selected()) {
            repository.insertResult(requestId, item);
        }
        UUID qualityRunId = qualityService.record(requestId, degraded, heavyRank.fallbackUsed() ? 1 : 0, sourceTimeout ? 1 : 0, slate.partialResult() ? 1 : 0, warnings);
        trace.put("servingQualityRunId", qualityRunId.toString());
        repository.updateServingTrace(requestId, trace, Map.of("servedItems", slate.selected()));
        for (ServedItem item : slate.selected()) {
            fatigueService.recordServed(profileId, item.candidateProfileId(), item.sourceTypes().isEmpty() ? null : item.sourceTypes().getFirst(), 1);
        }
        trace.put("requestId", requestId.toString());
        return new MultiStageServingResponse(requestId, surfaceKey, slate.selected(), degraded, requestId, trace, warnings);
    }

    private Map<String, Object> trace(SurfaceConfig surface, UUID sessionId, UUID sourceRoutingPlanId, Map<String, Object> budgetReasons, List<SourceCallResult> sourceResults, PreRankRun preRank, HeavyRankRun heavyRank, SlateOptimizationRun slate, List<String> warnings, SessionIntentState intent) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("surfaceKey", surface.surfaceKey());
        trace.put("sessionId", sessionId == null ? null : sessionId.toString());
        trace.put("sourceRoutingPlanId", sourceRoutingPlanId.toString());
        trace.put("preRankRunId", preRank.id().toString());
        trace.put("heavyRankRunId", heavyRank.id().toString());
        trace.put("slateOptimizationRunId", slate.id().toString());
        trace.put("sourceRoutingPlan", surface.allowedSources());
        trace.put("sourceAdaptation", budgetReasons);
        trace.put("sourceCallsAndDurations", sourceResults);
        trace.put("rawCandidateCounts", sourceResults.stream().mapToInt(SourceCallResult::returnedCount).sum());
        trace.put("preRankSurvivorCount", preRank.survivors().size());
        trace.put("heavyRankCandidateCount", heavyRank.ranked().size());
        trace.put("modelFallbackInfo", Map.of("modelBacked", heavyRank.modelBacked(), "fallbackUsed", heavyRank.fallbackUsed(), "fallbackReason", heavyRank.fallbackReason() == null ? "" : heavyRank.fallbackReason()));
        trace.put("slateOptimizerDecisions", Map.of("selected", slate.selected().size(), "dropped", slate.dropped().size(), "partial", slate.partialResult()));
        trace.put("fatigueSuppressions", preRank.filtered().stream().filter(item -> "FATIGUE_SUPPRESSED".equals(item.filteredReason())).toList());
        trace.put("sessionIntentAdjustments", intent == null ? Map.of("applied", false) : Map.of("applied", true, "sourceWeights", intent.sourceWeights(), "explanation", intent.explanation()));
        trace.put("sloDegradationResults", warnings);
        trace.put("finalServedCandidates", slate.selected());
        trace.put("warnings", warnings);
        return trace;
    }
}
