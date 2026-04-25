package com.matchgraph.api.interleaving;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.matchgraph.api.ranking.RankingReplayItem;
import com.matchgraph.api.ranking.RankingService;
import com.matchgraph.api.retrieval.HardExclusionService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InterleavingService {

    private final InterleavingRepository repository;
    private final RankingService rankingService;
    private final HardExclusionService hardExclusionService;

    public InterleavingService(
        InterleavingRepository repository,
        RankingService rankingService,
        HardExclusionService hardExclusionService
    ) {
        this.repository = repository;
        this.rankingService = rankingService;
        this.hardExclusionService = hardExclusionService;
    }

    @Transactional
    public InterleavingExperiment createExperiment(InterleavingExperimentRequest request) {
        validateExperiment(request);
        repository.createExperiment(request);
        return getExperiment(request.experimentKey());
    }

    public InterleavingExperiment getExperiment(String experimentKey) {
        return repository.findExperiment(experimentKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "interleaving experiment not found"));
    }

    @Transactional
    public InterleavingSession createSession(UUID profileId, String experimentKey, InterleavingSessionRequest request) {
        if (request == null || request.featureSnapshotRunId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "featureSnapshotRunId is required");
        }
        InterleavingExperiment experiment = getExperiment(experimentKey);
        if (!"ACTIVE".equals(experiment.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "interleaving experiment must be ACTIVE");
        }
        int limit = request.limit() == null ? 20 : Math.max(1, Math.min(100, request.limit()));
        Map<String, Object> context = request.rankingContext() == null ? Map.of() : request.rankingContext();
        List<RankingReplayItem> a = safeRank(profileId, request.featureSnapshotRunId(), experiment.rankerAVersion(), limit, context);
        List<RankingReplayItem> b = safeRank(profileId, request.featureSnapshotRunId(), experiment.rankerBVersion(), limit, context);
        UUID sessionId = repository.createSession(experiment, profileId, request.featureSnapshotRunId(), Map.of(
            "limit", limit,
            "normalFeedMutation", false,
            "method", "TEAM_DRAFT"
        ));
        List<InterleavingItem> items = teamDraft(sessionId, a, b, limit);
        items.forEach(repository::insertItem);
        repository.completeSession(sessionId, Map.of(
            "itemCount", items.size(),
            "rankerAItemCount", items.stream().filter(item -> "A".equals(item.attributedRanker())).count(),
            "rankerBItemCount", items.stream().filter(item -> "B".equals(item.attributedRanker())).count(),
            "normalFeedMutation", false
        ));
        return getSession(sessionId);
    }

    public InterleavingSession getSession(UUID sessionId) {
        return repository.findSession(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "interleaving session not found"));
    }

    @Transactional
    public InterleavingOutcome outcome(UUID sessionId, InterleavingOutcomeRequest request) {
        InterleavingSession session = getSession(sessionId);
        InterleavingItem item = repository.findItem(sessionId, request.candidateProfileId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "candidate is not in interleaving session"));
        BigDecimal reward = request.rewardValue() == null ? defaultReward(request.outcomeEventType()) : request.rewardValue();
        String winner = winner(sessionId, item.attributedRanker(), reward);
        Map<String, Object> summary = summary(sessionId, winner);
        UUID id = repository.insertOutcome(
            sessionId,
            item,
            request.interactionEventId(),
            request.outcomeEventType(),
            reward,
            winner,
            summary
        );
        return repository.outcomes(sessionId).stream()
            .filter(outcome -> outcome.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "interleaving outcome was not persisted"));
    }

    public Map<String, Object> summary(String experimentKey) {
        InterleavingExperiment experiment = getExperiment(experimentKey);
        return Map.of(
            "experimentKey", experiment.experimentKey(),
            "method", experiment.method(),
            "rankerAVersion", experiment.rankerAVersion(),
            "rankerBVersion", experiment.rankerBVersion(),
            "winnerCalculation", "per-session outcomes; INSUFFICIENT_DATA when no outcomes exist"
        );
    }

    private List<RankingReplayItem> safeRank(UUID profileId, UUID snapshotRunId, String rankingVersion, int limit, Map<String, Object> context) {
        return rankingService.rankStoredSnapshot(profileId, snapshotRunId, rankingVersion, limit, context).stream()
            .filter(item -> hardExclusionService.exclusionReason(profileId, item.candidateProfileId()).isEmpty())
            .toList();
    }

    private List<InterleavingItem> teamDraft(UUID sessionId, List<RankingReplayItem> a, List<RankingReplayItem> b, int limit) {
        Map<UUID, RankingReplayItem> aByCandidate = a.stream().collect(Collectors.toMap(RankingReplayItem::candidateProfileId, Function.identity()));
        Map<UUID, RankingReplayItem> bByCandidate = b.stream().collect(Collectors.toMap(RankingReplayItem::candidateProfileId, Function.identity()));
        LinkedHashSet<UUID> selected = new LinkedHashSet<>();
        List<InterleavingItem> items = new ArrayList<>();
        int ai = 0;
        int bi = 0;
        boolean chooseA = true;
        while (items.size() < limit && (ai < a.size() || bi < b.size())) {
            RankingReplayItem next = null;
            String ranker = chooseA ? "A" : "B";
            if (chooseA) {
                while (ai < a.size() && selected.contains(a.get(ai).candidateProfileId())) {
                    ai++;
                }
                if (ai < a.size()) {
                    next = a.get(ai++);
                }
            } else {
                while (bi < b.size() && selected.contains(b.get(bi).candidateProfileId())) {
                    bi++;
                }
                if (bi < b.size()) {
                    next = b.get(bi++);
                }
            }
            chooseA = !chooseA;
            if (next == null || !selected.add(next.candidateProfileId())) {
                continue;
            }
            RankingReplayItem aItem = aByCandidate.get(next.candidateProfileId());
            RankingReplayItem bItem = bByCandidate.get(next.candidateProfileId());
            Map<String, Object> score = new LinkedHashMap<>();
            score.put("rankerAScore", aItem == null ? null : aItem.finalScore());
            score.put("rankerBScore", bItem == null ? null : bItem.finalScore());
            items.add(new InterleavingItem(
                UUID.randomUUID(),
                sessionId,
                next.candidateProfileId(),
                items.size() + 1,
                ranker,
                aItem == null ? null : aItem.position(),
                bItem == null ? null : bItem.position(),
                score,
                null
            ));
        }
        return items.stream()
            .sorted(Comparator.comparing(InterleavingItem::position))
            .toList();
    }

    private String winner(UUID sessionId, String attributedRanker, BigDecimal newReward) {
        BigDecimal a = BigDecimal.ZERO;
        BigDecimal b = BigDecimal.ZERO;
        for (InterleavingOutcome outcome : repository.outcomes(sessionId)) {
            if ("A".equals(outcome.attributedRanker())) {
                a = a.add(outcome.rewardValue());
            } else if ("B".equals(outcome.attributedRanker())) {
                b = b.add(outcome.rewardValue());
            }
        }
        if ("A".equals(attributedRanker)) {
            a = a.add(newReward);
        } else if ("B".equals(attributedRanker)) {
            b = b.add(newReward);
        }
        if (a.signum() == 0 && b.signum() == 0) {
            return "INSUFFICIENT_DATA";
        }
        int comparison = a.compareTo(b);
        if (comparison > 0) {
            return "A";
        }
        if (comparison < 0) {
            return "B";
        }
        return "TIE";
    }

    private Map<String, Object> summary(UUID sessionId, String winner) {
        List<InterleavingOutcome> outcomes = repository.outcomes(sessionId);
        return Map.of(
            "outcomeCount", outcomes.size() + 1,
            "winner", winner,
            "insufficientData", "INSUFFICIENT_DATA".equals(winner)
        );
    }

    private BigDecimal defaultReward(String eventType) {
        return switch (eventType == null ? "" : eventType) {
            case "PROFILE_VIEW" -> BigDecimal.valueOf(0.25);
            case "LIKE" -> BigDecimal.ONE;
            case "MATCH_CREATED" -> BigDecimal.valueOf(2);
            case "PASS" -> BigDecimal.valueOf(-0.25);
            case "BLOCK", "REPORT" -> BigDecimal.valueOf(-2);
            default -> BigDecimal.ZERO;
        };
    }

    private void validateExperiment(InterleavingExperimentRequest request) {
        if (request == null || request.experimentKey() == null || request.experimentKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "experimentKey is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (request.rankerAVersion() == null || request.rankerAVersion().isBlank() || request.rankerBVersion() == null || request.rankerBVersion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ranker versions are required");
        }
    }
}
