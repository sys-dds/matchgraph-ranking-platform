package com.matchgraph.api.reward;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LongTermRewardService {

    private final LongTermRewardRepository repository;
    private final RewardObjectiveService objectiveService;

    public LongTermRewardService(LongTermRewardRepository repository, RewardObjectiveService objectiveService) {
        this.repository = repository;
        this.objectiveService = objectiveService;
    }

    @Transactional
    public LongTermRewardRun create(LongTermRewardRequest request) {
        if (request == null || (request.datasetRunId() == null && request.decisionLogId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetRunId or decisionLogId is required");
        }
        int window = request.delayedWindowHours() == null ? 72 : Math.max(1, request.delayedWindowHours());
        boolean includeNeutral = request.includeNeutral() == null || request.includeNeutral();
        boolean updateTrainingLabels = Boolean.TRUE.equals(request.updateTrainingLabels());
        UUID runId = repository.createRun(request, window, includeNeutral, updateTrainingLabels);
        List<LongTermRewardRepository.RewardFact> facts = request.datasetRunId() != null
            ? repository.examplesForDataset(request.datasetRunId())
            : repository.examplesForDecision(request.decisionLogId());
        BigDecimal shortSum = BigDecimal.ZERO;
        BigDecimal longSum = BigDecimal.ZERO;
        BigDecimal finalSum = BigDecimal.ZERO;
        int labelled = 0;
        for (LongTermRewardRepository.RewardFact fact : facts) {
            RewardObjectiveService.RewardScore score = objectiveService.score(repository.events(fact, window), repository.activeMatch(fact, window), includeNeutral);
            repository.insertLabel(runId, fact, scale(score.shortTermReward()), scale(score.longTermReward()), scale(score.finalRewardValue()), score.components());
            if (updateTrainingLabels) {
                repository.upsertTrainingLabel(fact.trainingExampleId(), scale(score.finalRewardValue()), window);
            }
            shortSum = shortSum.add(score.shortTermReward());
            longSum = longSum.add(score.longTermReward());
            finalSum = finalSum.add(score.finalRewardValue());
            labelled++;
        }
        int denominator = Math.max(1, labelled);
        Map<String, Object> summary = Map.of(
            "semantics", "Product-quality delayed reward proxy, not true user happiness.",
            "conversationStarted", "NOT_AVAILABLE",
            "delayedWindowHours", window
        );
        repository.insertResult(runId, facts.size(), labelled, avg(shortSum, denominator), avg(longSum, denominator), avg(finalSum, denominator), summary);
        repository.completeRun(runId, summary);
        return get(runId);
    }

    public LongTermRewardRun get(UUID runId) {
        return repository.findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "long-term reward run not found"));
    }

    public Map<String, Object> summary() {
        return repository.summary();
    }

    private BigDecimal avg(BigDecimal value, int denominator) {
        return value.divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }
}
