package com.matchgraph.api.demo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.matchgraph.api.bandit.BanditArmRequest;
import com.matchgraph.api.bandit.BanditDecision;
import com.matchgraph.api.bandit.BanditDecisionRequest;
import com.matchgraph.api.bandit.BanditPolicyRequest;
import com.matchgraph.api.bandit.BanditPolicyService;
import com.matchgraph.api.bandit.BanditReward;
import com.matchgraph.api.bandit.BanditRewardRequest;
import com.matchgraph.api.bandit.BanditDecisionService;
import com.matchgraph.api.bandit.BanditRewardService;
import com.matchgraph.api.evaluation.CounterfactualEvaluationRequest;
import com.matchgraph.api.evaluation.CounterfactualEvaluationResponse;
import com.matchgraph.api.evaluation.CounterfactualEvaluationService;
import com.matchgraph.api.evaluation.OfflineEvaluationRequest;
import com.matchgraph.api.evaluation.OfflineEvaluationResponse;
import com.matchgraph.api.evaluation.OfflineEvaluationService;
import com.matchgraph.api.experiment.ExperimentService;
import com.matchgraph.api.experiment.RankingExperimentAssignment;
import com.matchgraph.api.experiment.RankingExperimentCreateRequest;
import com.matchgraph.api.experiment.RankingExperimentVariantRequest;
import com.matchgraph.api.explainability.CandidateExplanation;
import com.matchgraph.api.explainability.RankingExplainabilityService;
import com.matchgraph.api.exposure.ExposureControlPolicy;
import com.matchgraph.api.exposure.ExposurePolicyRequest;
import com.matchgraph.api.exposure.ExposurePolicyService;
import com.matchgraph.api.features.FeatureSnapshotRun;
import com.matchgraph.api.features.FeatureSnapshotService;
import com.matchgraph.api.feed.DiscoveryFeedService;
import com.matchgraph.api.feed.FeedItem;
import com.matchgraph.api.feed.FeedRefreshRequest;
import com.matchgraph.api.feed.FeedSnapshot;
import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.graph.GraphEdgeService;
import com.matchgraph.api.interleaving.InterleavingExperimentRequest;
import com.matchgraph.api.interleaving.InterleavingOutcome;
import com.matchgraph.api.interleaving.InterleavingOutcomeRequest;
import com.matchgraph.api.interleaving.InterleavingService;
import com.matchgraph.api.interleaving.InterleavingSession;
import com.matchgraph.api.interleaving.InterleavingSessionRequest;
import com.matchgraph.api.ranking.RankingDecision;
import com.matchgraph.api.ranking.RankingService;
import com.matchgraph.api.retrieval.CandidateRetrievalRun;
import com.matchgraph.api.retrieval.CandidateRetrievalService;
import com.matchgraph.api.retrieval.RunRetrievalRequest;
import com.matchgraph.api.shadow.ChampionChallengerConfigRequest;
import com.matchgraph.api.shadow.ChampionChallengerEvaluateRequest;
import com.matchgraph.api.shadow.ChampionChallengerService;
import com.matchgraph.api.shadow.ShadowRankingRun;
import com.matchgraph.api.synthetic.GroundTruthEvaluationService;
import com.matchgraph.api.synthetic.SyntheticEvaluationRequest;
import com.matchgraph.api.synthetic.SyntheticEvaluationRun;
import com.matchgraph.api.synthetic.SyntheticPopulationRequest;
import com.matchgraph.api.synthetic.SyntheticPopulationRun;
import com.matchgraph.api.synthetic.SyntheticPopulationService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RankingScienceDemoService {

    private static final Set<String> CRITICAL_STEPS = Set.of(
        "synthetic_population",
        "retrieval",
        "feature_snapshots",
        "baseline_ranking",
        "feed_refresh",
        "explainability",
        "synthetic_ground_truth_evaluation"
    );
    private static final Set<String> OPTIONAL_STEPS = Set.of(
        "bandit",
        "interleaving",
        "exposure",
        "offline_evaluation",
        "counterfactual_evaluation"
    );

    private final RankingScienceDemoRepository repository;
    private final SyntheticPopulationService syntheticPopulationService;
    private final CandidateRetrievalService retrievalService;
    private final FeatureSnapshotService featureSnapshotService;
    private final RankingService rankingService;
    private final DiscoveryFeedService feedService;
    private final ExperimentService experimentService;
    private final ChampionChallengerService championChallengerService;
    private final RankingExplainabilityService explainabilityService;
    private final BanditPolicyService banditPolicyService;
    private final BanditDecisionService banditDecisionService;
    private final BanditRewardService banditRewardService;
    private final InterleavingService interleavingService;
    private final ExposurePolicyService exposurePolicyService;
    private final OfflineEvaluationService offlineEvaluationService;
    private final CounterfactualEvaluationService counterfactualEvaluationService;
    private final GroundTruthEvaluationService groundTruthEvaluationService;
    private final GraphEdgeService graphEdgeService;

    public RankingScienceDemoService(
        RankingScienceDemoRepository repository,
        SyntheticPopulationService syntheticPopulationService,
        CandidateRetrievalService retrievalService,
        FeatureSnapshotService featureSnapshotService,
        RankingService rankingService,
        DiscoveryFeedService feedService,
        ExperimentService experimentService,
        ChampionChallengerService championChallengerService,
        RankingExplainabilityService explainabilityService,
        BanditPolicyService banditPolicyService,
        BanditDecisionService banditDecisionService,
        BanditRewardService banditRewardService,
        InterleavingService interleavingService,
        ExposurePolicyService exposurePolicyService,
        OfflineEvaluationService offlineEvaluationService,
        CounterfactualEvaluationService counterfactualEvaluationService,
        GroundTruthEvaluationService groundTruthEvaluationService,
        GraphEdgeService graphEdgeService
    ) {
        this.repository = repository;
        this.syntheticPopulationService = syntheticPopulationService;
        this.retrievalService = retrievalService;
        this.featureSnapshotService = featureSnapshotService;
        this.rankingService = rankingService;
        this.feedService = feedService;
        this.experimentService = experimentService;
        this.championChallengerService = championChallengerService;
        this.explainabilityService = explainabilityService;
        this.banditPolicyService = banditPolicyService;
        this.banditDecisionService = banditDecisionService;
        this.banditRewardService = banditRewardService;
        this.interleavingService = interleavingService;
        this.exposurePolicyService = exposurePolicyService;
        this.offlineEvaluationService = offlineEvaluationService;
        this.counterfactualEvaluationService = counterfactualEvaluationService;
        this.groundTruthEvaluationService = groundTruthEvaluationService;
        this.graphEdgeService = graphEdgeService;
    }

    public RankingScienceDemoReport run(RankingScienceDemoRequest request) {
        long seed = request == null || request.seed() == null ? 29029L : request.seed();
        int profileCount = request == null || request.profileCount() == null ? 10 : request.profileCount();
        int clusterCount = request == null || request.clusterCount() == null ? 3 : request.clusterCount();
        UUID demoRunId = repository.createRun(seed, request == null ? Map.of() : request.config());
        DemoState state = new DemoState(demoRunId, seed);

        step(state, "synthetic_population", () -> {
            state.syntheticPopulation = syntheticPopulationService.create(new SyntheticPopulationRequest(seed, profileCount, clusterCount, BigDecimal.valueOf(0.35), Map.of("demo", true)));
            state.actorProfileId = state.syntheticPopulation.profiles().getFirst().profileId();
            return ids("syntheticPopulationRunId", state.syntheticPopulation.id(), "actorProfileId", state.actorProfileId);
        });
        step(state, "retrieval", () -> {
            state.retrieval = retrievalService.run(state.actorProfileId, new RunRetrievalRequest(30, null, null));
            return ids("retrievalRunId", state.retrieval.id(), "candidateCount", state.retrieval.candidates().size());
        });
        step(state, "feature_snapshots", () -> {
            state.snapshots = featureSnapshotService.createFromRetrieval(state.actorProfileId, state.retrieval.id());
            return ids("featureSnapshotRunId", state.snapshots.id(), "candidateCount", state.snapshots.candidateCount());
        });
        step(state, "baseline_ranking", () -> {
            state.rankingDecision = rankingService.run(state.actorProfileId, state.snapshots.id(), "v1_balanced", 10, "RANKING_RUN");
            return ids("rankingDecisionLogId", state.rankingDecision.id(), "servedCount", state.rankingDecision.servedCount());
        });
        step(state, "experiment_assignment", () -> {
            state.experimentKey = "demo-exp-" + seed + "-" + demoRunId.toString().substring(0, 8);
            experimentService.create(new RankingExperimentCreateRequest(
                state.experimentKey,
                "Ranking science demo experiment",
                "ACTIVE",
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(10),
                Map.of("demo", true),
                List.of(
                    new RankingExperimentVariantRequest("graph", "v1_graph_affinity", BigDecimal.valueOf(45), Map.of()),
                    new RankingExperimentVariantRequest("vector", "v1_vector_affinity", BigDecimal.valueOf(45), Map.of())
                )
            ));
            state.assignment = experimentService.assign(state.actorProfileId, state.experimentKey);
            return ids("experimentKey", state.experimentKey, "assignmentId", state.assignment.id());
        });
        step(state, "feed_refresh", () -> {
            state.feed = feedService.refresh(state.actorProfileId, new FeedRefreshRequest(state.retrieval.id(), 10, state.experimentKey));
            state.rankingDecision = rankingService.get(state.actorProfileId, state.feed.rankingDecisionLogId());
            return ids("feedSnapshotId", state.feed.id(), "rankingDecisionLogId", state.rankingDecision.id());
        });
        step(state, "shadow_ranking_champion_challenger", () -> {
            String configKey = "demo-cc-" + seed + "-" + demoRunId.toString().substring(0, 8);
            championChallengerService.create(new ChampionChallengerConfigRequest(
                configKey,
                "Demo champion challenger",
                "ACTIVE",
                state.rankingDecision.rankingVersion(),
                alternate(state.rankingDecision.rankingVersion()),
                Map.of("maxSafetyRegressionCount", 0)
            ));
            state.championDecision = championChallengerService.evaluate(configKey, new ChampionChallengerEvaluateRequest(state.rankingDecision.id(), 10));
            state.shadowRun = null;
            return ids("championChallengerDecisionId", state.championDecision.id(), "shadowRunId", state.championDecision.shadowRunId());
        });
        step(state, "explainability", () -> {
            FeedItem shown = state.feed.items().getFirst();
            CandidateExplanation shownExplanation = explainabilityService.whyShown(state.actorProfileId, shown.candidateProfileId());
            UUID hiddenCandidate = state.syntheticPopulation.profiles().getLast().profileId();
            graphEdgeService.block(state.actorProfileId, new GraphActionRequest(hiddenCandidate, "demo hidden explanation"));
            CandidateExplanation hiddenExplanation = explainabilityService.whyHidden(state.actorProfileId, hiddenCandidate);
            state.explanationRequestIds.add(shownExplanation.requestId());
            state.explanationRequestIds.add(hiddenExplanation.requestId());
            return Map.of("shownExplanationRequestId", shownExplanation.requestId(), "hiddenExplanationRequestId", hiddenExplanation.requestId());
        });
        step(state, "bandit", () -> {
            String policyKey = "demo-bandit-" + seed + "-" + demoRunId.toString().substring(0, 8);
            banditPolicyService.create(new BanditPolicyRequest(
                policyKey,
                "Demo bandit",
                "ACTIVE",
                "EPSILON_GREEDY",
                BigDecimal.ZERO,
                Map.of("LIKE", 1, "PROFILE_VIEW", 0.25),
                Map.of("demo", true),
                List.of(
                    new BanditArmRequest("graph", "GRAPH_TWO_HOP", "GRAPH_TWO_HOP boost", BigDecimal.ONE, Map.of()),
                    new BanditArmRequest("vector", "VECTOR_SIMILARITY", "VECTOR_SIMILARITY boost", BigDecimal.ONE, Map.of())
                )
            ));
            UUID candidate = state.feed.items().getFirst().candidateProfileId();
            BanditDecision decision = banditDecisionService.decide(state.actorProfileId, policyKey, new BanditDecisionRequest(candidate, null, "default", Map.of("demoRunId", demoRunId.toString()), false));
            BanditReward reward = banditRewardService.reward(new BanditRewardRequest(decision.id(), policyKey, state.actorProfileId, candidate, "LIKE", null, null));
            state.banditDecisionId = decision.id();
            state.banditRewardId = reward.id();
            return ids("banditDecisionId", decision.id(), "banditRewardId", reward.id());
        });
        step(state, "interleaving", () -> {
            String key = "demo-interleave-" + seed + "-" + demoRunId.toString().substring(0, 8);
            interleavingService.createExperiment(new InterleavingExperimentRequest(key, "Demo interleaving", "ACTIVE", "v1_balanced", alternate("v1_balanced"), Map.of("demo", true)));
            InterleavingSession session = interleavingService.createSession(state.actorProfileId, key, new InterleavingSessionRequest(state.feed.featureSnapshotRunId(), 10, state.rankingDecision.rankingContext()));
            InterleavingOutcome outcome = interleavingService.outcome(session.id(), new InterleavingOutcomeRequest(session.items().getFirst().candidateProfileId(), null, "LIKE", BigDecimal.ONE));
            state.interleavingSessionId = session.id();
            return ids("interleavingSessionId", session.id(), "outcomeId", outcome.id(), "winner", outcome.winner());
        });
        step(state, "exposure", () -> {
            state.exposurePolicyKey = "demo-exposure-" + seed + "-" + demoRunId.toString().substring(0, 8);
            ExposureControlPolicy policy = exposurePolicyService.create(new ExposurePolicyRequest(
                state.exposurePolicyKey,
                "Demo exposure",
                "ACTIVE",
                3,
                3,
                24,
                3,
                BigDecimal.valueOf(0.10),
                BigDecimal.valueOf(0.25),
                BigDecimal.valueOf(0.05),
                Map.of("demo", true)
            ));
            state.feed = feedService.refresh(state.actorProfileId, new FeedRefreshRequest(state.retrieval.id(), 10, state.experimentKey));
            state.rankingDecision = rankingService.get(state.actorProfileId, state.feed.rankingDecisionLogId());
            return ids("exposurePolicyKey", policy.policyKey(), "feedSnapshotId", state.feed.id());
        });
        step(state, "offline_evaluation", () -> {
            state.offline = offlineEvaluationService.evaluate(new OfflineEvaluationRequest(state.rankingDecision.rankingVersion(), null, null, 10, state.experimentKey));
            return ids("offlineEvaluationRunId", state.offline.run().id(), "precisionAtK", state.offline.result().precisionAtK());
        });
        step(state, "counterfactual_evaluation", () -> {
            state.counterfactual = counterfactualEvaluationService.evaluate(new CounterfactualEvaluationRequest(state.rankingDecision.id(), alternate(state.rankingDecision.rankingVersion()), 10));
            return ids("counterfactualRunId", state.counterfactual.run().id(), "itemCount", state.counterfactual.items().size());
        });
        step(state, "synthetic_ground_truth_evaluation", () -> {
            state.syntheticEvaluation = groundTruthEvaluationService.evaluate(new SyntheticEvaluationRequest(state.syntheticPopulation.id(), state.rankingDecision.id(), state.rankingDecision.rankingVersion(), 10));
            return ids("syntheticEvaluationRunId", state.syntheticEvaluation.id(), "precisionAtK", state.syntheticEvaluation.result().precisionAtK());
        });

        RankingScienceDemoReport report = state.report();
        repository.completeRun(demoRunId, report.failedCriticalStepCount() == 0 ? "COMPLETED" : "FAILED_CRITICAL", reportMap(report));
        return report;
    }

    public RankingScienceDemoRun get(UUID demoRunId) {
        return repository.findRun(demoRunId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ranking science demo run not found"));
    }

    private void step(DemoState state, String name, Callable<Map<String, Object>> work) {
        long started = System.nanoTime();
        try {
            Map<String, Object> result = work.call();
            long duration = (System.nanoTime() - started) / 1_000_000;
            state.durationByStep.put(name, duration);
            repository.insertStep(state.demoRunId, name, "COMPLETED", result, duration);
            state.completedStepCount++;
        } catch (Exception exception) {
            long duration = (System.nanoTime() - started) / 1_000_000;
            Map<String, Object> result = Map.of("reason", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            state.durationByStep.put(name, duration);
            if (CRITICAL_STEPS.contains(name)) {
                Map<String, Object> failure = Map.of("stepName", name, "reason", result.get("reason"));
                state.criticalStepFailures.add(failure);
                repository.insertStep(state.demoRunId, name, "FAILED_CRITICAL", result, duration);
            } else if (OPTIONAL_STEPS.contains(name)) {
                Map<String, Object> skipped = Map.of("stepName", name, "reason", result.get("reason"));
                state.optionalSkippedSteps.add(skipped);
                state.skippedSteps.add(skipped);
                repository.insertStep(state.demoRunId, name, "SKIPPED_OPTIONAL", result, duration);
            } else {
                Map<String, Object> failure = Map.of("stepName", name, "reason", result.get("reason"));
                state.criticalStepFailures.add(failure);
                repository.insertStep(state.demoRunId, name, "FAILED_CRITICAL", result, duration);
            }
        }
    }

    private Map<String, Object> ids(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    private String alternate(String rankingVersion) {
        return "v1_graph_affinity".equals(rankingVersion) ? "v1_vector_affinity" : "v1_graph_affinity";
    }

    private Map<String, Object> reportMap(RankingScienceDemoReport report) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("syntheticPopulationRunId", report.syntheticPopulationRunId());
        result.put("actorProfileId", report.actorProfileId());
        result.put("retrievalRunId", report.retrievalRunId());
        result.put("featureSnapshotRunId", report.featureSnapshotRunId());
        result.put("rankingDecisionLogId", report.rankingDecisionLogId());
        result.put("feedSnapshotId", report.feedSnapshotId());
        result.put("experimentKey", report.experimentKey());
        result.put("assignmentId", report.assignmentId());
        result.put("shadowRunId", report.shadowRunId());
        result.put("explanationRequestIds", report.explanationRequestIds());
        result.put("banditDecisionId", report.banditDecisionId());
        result.put("banditRewardId", report.banditRewardId());
        result.put("interleavingSessionId", report.interleavingSessionId());
        result.put("exposurePolicyKey", report.exposurePolicyKey());
        result.put("offlineEvaluationRunId", report.offlineEvaluationRunId());
        result.put("counterfactualRunId", report.counterfactualRunId());
        result.put("syntheticEvaluationRunId", report.syntheticEvaluationRunId());
        result.put("keyMetrics", report.keyMetrics());
        result.put("skippedSteps", report.skippedSteps());
        result.put("criticalStepFailures", report.criticalStepFailures());
        result.put("optionalSkippedSteps", report.optionalSkippedSteps());
        result.put("completedStepCount", report.completedStepCount());
        result.put("failedCriticalStepCount", report.failedCriticalStepCount());
        result.put("skippedOptionalStepCount", report.skippedOptionalStepCount());
        return result;
    }

    private final class DemoState {
        private final UUID demoRunId;
        private final long seed;
        private SyntheticPopulationRun syntheticPopulation;
        private UUID actorProfileId;
        private CandidateRetrievalRun retrieval;
        private FeatureSnapshotRun snapshots;
        private RankingDecision rankingDecision;
        private String experimentKey;
        private RankingExperimentAssignment assignment;
        private FeedSnapshot feed;
        private com.matchgraph.api.shadow.ChampionChallengerDecision championDecision;
        private ShadowRankingRun shadowRun;
        private final List<UUID> explanationRequestIds = new ArrayList<>();
        private UUID banditDecisionId;
        private UUID banditRewardId;
        private UUID interleavingSessionId;
        private String exposurePolicyKey;
        private OfflineEvaluationResponse offline;
        private CounterfactualEvaluationResponse counterfactual;
        private SyntheticEvaluationRun syntheticEvaluation;
        private final Map<String, Long> durationByStep = new LinkedHashMap<>();
        private final List<Map<String, Object>> skippedSteps = new ArrayList<>();
        private final List<Map<String, Object>> criticalStepFailures = new ArrayList<>();
        private final List<Map<String, Object>> optionalSkippedSteps = new ArrayList<>();
        private int completedStepCount;

        private DemoState(UUID demoRunId, long seed) {
            this.demoRunId = demoRunId;
            this.seed = seed;
        }

        private RankingScienceDemoReport report() {
            Map<String, Object> metrics = new LinkedHashMap<>();
            if (offline != null) {
                metrics.put("offlinePrecisionAtK", offline.result().precisionAtK());
                metrics.put("offlineNdcgAtK", offline.result().ndcgAtK());
            }
            if (syntheticEvaluation != null && syntheticEvaluation.result() != null) {
                metrics.put("groundTruthPrecisionAtK", syntheticEvaluation.result().precisionAtK());
                metrics.put("groundTruthNdcgAtK", syntheticEvaluation.result().ndcgAtK());
            }
            metrics.put("skippedStepCount", skippedSteps.size());
            metrics.put("criticalStepFailureCount", criticalStepFailures.size());
            metrics.put("optionalSkippedStepCount", optionalSkippedSteps.size());
            return new RankingScienceDemoReport(
                demoRunId,
                syntheticPopulation == null ? null : syntheticPopulation.id(),
                actorProfileId,
                retrieval == null ? null : retrieval.id(),
                feed == null ? (snapshots == null ? null : snapshots.id()) : feed.featureSnapshotRunId(),
                rankingDecision == null ? null : rankingDecision.id(),
                feed == null ? null : feed.id(),
                experimentKey,
                assignment == null ? null : assignment.id(),
                championDecision == null ? null : championDecision.shadowRunId(),
                explanationRequestIds,
                banditDecisionId,
                banditRewardId,
                interleavingSessionId,
                exposurePolicyKey,
                offline == null ? null : offline.run().id(),
                counterfactual == null ? null : counterfactual.run().id(),
                syntheticEvaluation == null ? null : syntheticEvaluation.id(),
                metrics,
                durationByStep,
                skippedSteps,
                criticalStepFailures,
                optionalSkippedSteps,
                completedStepCount,
                criticalStepFailures.size(),
                optionalSkippedSteps.size()
            );
        }
    }
}
