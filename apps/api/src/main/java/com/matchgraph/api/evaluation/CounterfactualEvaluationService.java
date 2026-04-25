package com.matchgraph.api.evaluation;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.matchgraph.api.ranking.RankingDecision;
import com.matchgraph.api.ranking.RankingDecisionItem;
import com.matchgraph.api.ranking.RankingReplayItem;
import com.matchgraph.api.ranking.RankingService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CounterfactualEvaluationService {

    private final CounterfactualEvaluationRepository counterfactualEvaluationRepository;
    private final RankingService rankingService;

    public CounterfactualEvaluationService(
        CounterfactualEvaluationRepository counterfactualEvaluationRepository,
        RankingService rankingService
    ) {
        this.counterfactualEvaluationRepository = counterfactualEvaluationRepository;
        this.rankingService = rankingService;
    }

    @Transactional
    public CounterfactualEvaluationResponse evaluate(CounterfactualEvaluationRequest request) {
        validate(request);
        int k = request.k() == null ? 10 : request.k();
        RankingDecision baseline = rankingService.get(request.baselineDecisionLogId());
        CounterfactualEvaluationRun run = counterfactualEvaluationRepository.createRun(UUID.randomUUID(), request, k);
        List<RankingReplayItem> counterfactual = rankingService.rankStoredSnapshot(
            baseline.profileId(),
            baseline.featureSnapshotRunId(),
            request.candidateRankingVersion(),
            k,
            baseline.rankingContext()
        );
        persistComparisons(run.id(), baseline, counterfactual, k);
        counterfactualEvaluationRepository.completeRun(run.id(), summary(baseline, counterfactual, k));
        return get(run.id());
    }

    public CounterfactualEvaluationResponse get(UUID runId) {
        CounterfactualEvaluationRun run = counterfactualEvaluationRepository.run(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "counterfactual evaluation run not found"));
        return new CounterfactualEvaluationResponse(run, counterfactualEvaluationRepository.items(runId));
    }

    private void persistComparisons(UUID runId, RankingDecision baseline, List<RankingReplayItem> counterfactual, int k) {
        Map<UUID, RankingDecisionItem> original = baseline.items().stream()
            .collect(Collectors.toMap(RankingDecisionItem::candidateProfileId, Function.identity()));
        Map<UUID, RankingReplayItem> reranked = counterfactual.stream()
            .collect(Collectors.toMap(RankingReplayItem::candidateProfileId, Function.identity()));
        Set<UUID> candidates = new LinkedHashSet<>();
        original.keySet().stream().sorted().forEach(candidates::add);
        reranked.keySet().stream().sorted().forEach(candidates::add);
        for (UUID candidateId : candidates) {
            RankingDecisionItem originalItem = original.get(candidateId);
            RankingReplayItem rerankedItem = reranked.get(candidateId);
            String label = counterfactualEvaluationRepository.label(baseline.profileId(), candidateId, baseline.createdAt());
            counterfactualEvaluationRepository.insertItem(
                runId,
                candidateId,
                originalItem == null ? null : originalItem.position(),
                rerankedItem == null ? null : rerankedItem.position(),
                originalItem == null ? null : originalItem.finalScore(),
                rerankedItem == null ? null : rerankedItem.finalScore(),
                topKChange(originalItem == null ? null : originalItem.position(), rerankedItem == null ? null : rerankedItem.position(), k),
                label,
                metricDelta(label, originalItem == null ? null : originalItem.position(), rerankedItem == null ? null : rerankedItem.position(), k)
            );
        }
    }

    private Map<String, Object> summary(RankingDecision baseline, List<RankingReplayItem> counterfactual, int k) {
        List<UUID> originalTopK = baseline.items().stream().limit(k).map(RankingDecisionItem::candidateProfileId).toList();
        List<UUID> counterfactualTopK = counterfactual.stream().limit(k).map(RankingReplayItem::candidateProfileId).toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("originalOrder", originalTopK);
        summary.put("counterfactualOrder", counterfactualTopK);
        summary.put("unchangedTopK", originalTopK.stream().filter(counterfactualTopK::contains).count());
        summary.put("enteredTopK", counterfactualTopK.stream().filter(candidate -> !originalTopK.contains(candidate)).count());
        summary.put("droppedFromTopK", originalTopK.stream().filter(candidate -> !counterfactualTopK.contains(candidate)).count());
        summary.put("baselineDecisionLogId", baseline.id().toString());
        summary.put("baselineRetrievalRunId", baseline.retrievalRunId().toString());
        summary.put("baselineFeatureSnapshotRunId", baseline.featureSnapshotRunId().toString());
        summary.put("storedRankingContext", baseline.rankingContext());
        summary.put("mutationSemantics", "counterfactual evaluation reranks the stored feature snapshot run with stored ranking context; it does not run retrieval, create feature snapshots, or mutate feed snapshots");
        return summary;
    }

    private String topKChange(Integer originalPosition, Integer counterfactualPosition, int k) {
        boolean originalTopK = originalPosition != null && originalPosition <= k;
        boolean counterfactualTopK = counterfactualPosition != null && counterfactualPosition <= k;
        if (!originalTopK && counterfactualTopK) {
            return "ENTERED_TOP_K";
        }
        if (originalTopK && !counterfactualTopK) {
            return "DROPPED_FROM_TOP_K";
        }
        if (originalTopK) {
            return "UNCHANGED_TOP_K";
        }
        return "UNCHANGED_OUTSIDE_TOP_K";
    }

    private Map<String, Object> metricDelta(String label, Integer originalPosition, Integer counterfactualPosition, int k) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (label == null) {
            return delta;
        }
        BigDecimal originalCredit = credit(label, originalPosition, k);
        BigDecimal counterfactualCredit = credit(label, counterfactualPosition, k);
        delta.put("labelEventType", label);
        delta.put("topKCreditDelta", counterfactualCredit.subtract(originalCredit));
        return delta;
    }

    private BigDecimal credit(String label, Integer position, int k) {
        if (position == null || position > k || !Set.of("LIKE", "PROFILE_VIEW", "MATCH_CREATED").contains(label)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.ONE;
    }

    private void validate(CounterfactualEvaluationRequest request) {
        if (request == null || request.baselineDecisionLogId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baselineDecisionLogId is required");
        }
        if (request.candidateRankingVersion() == null || request.candidateRankingVersion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "candidateRankingVersion is required");
        }
        if (request.k() != null && (request.k() < 1 || request.k() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "k must be between 1 and 100");
        }
    }
}
