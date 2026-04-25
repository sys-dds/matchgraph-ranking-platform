package com.matchgraph.api.ranking;

import java.util.UUID;

import com.matchgraph.api.experiment.ExperimentService;
import com.matchgraph.api.experiment.RankingExperimentAssignment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles/{profileId}/ranking")
public class RankingController {

    private final RankingService rankingService;
    private final ExperimentService experimentService;

    public RankingController(RankingService rankingService, ExperimentService experimentService) {
        this.rankingService = rankingService;
        this.experimentService = experimentService;
    }

    @PostMapping("/run")
    public RankingDecision run(@PathVariable UUID profileId, @RequestBody RankingRunRequest request) {
        RankingExperimentAssignment assignment = assignment(profileId, request.experimentKey());
        String rankingVersion = assignment == null ? request.rankingVersion() : assignment.assignedRankingVersion();
        return rankingService.run(
            profileId,
            request.featureSnapshotRunId(),
            rankingVersion,
            request.limit(),
            "RANKING_RUN",
            assignment == null ? request.experimentKey() : assignment.experimentKey(),
            assignment == null ? null : assignment.assignedVariantKey(),
            assignment == null ? null : assignment.id(),
            null
        );
    }

    @GetMapping("/decisions/{decisionLogId}")
    public RankingDecision get(@PathVariable UUID profileId, @PathVariable UUID decisionLogId) {
        return rankingService.get(profileId, decisionLogId);
    }

    private RankingExperimentAssignment assignment(UUID profileId, String experimentKey) {
        if (experimentKey == null || experimentKey.isBlank()) {
            return null;
        }
        return experimentService.assign(profileId, experimentKey.trim());
    }
}
