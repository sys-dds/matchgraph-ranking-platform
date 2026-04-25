package com.matchgraph.api.explainability;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.matchgraph.api.exposure.ExposureRepository;
import com.matchgraph.api.retrieval.HardExclusionService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RankingExplainabilityService {

    private final RankingExplainabilityRepository repository;
    private final HardExclusionService hardExclusionService;
    private final ExposureRepository exposureRepository;

    public RankingExplainabilityService(
        RankingExplainabilityRepository repository,
        HardExclusionService hardExclusionService,
        ExposureRepository exposureRepository
    ) {
        this.repository = repository;
        this.hardExclusionService = hardExclusionService;
        this.exposureRepository = exposureRepository;
    }

    @Transactional
    public CandidateExplanation whyShown(UUID profileId, UUID candidateProfileId) {
        return explain(new RankingExplanationRequest(profileId, candidateProfileId, null, null, "WHY_SHOWN"));
    }

    @Transactional
    public CandidateExplanation whyHidden(UUID profileId, UUID candidateProfileId) {
        return explain(new RankingExplanationRequest(profileId, candidateProfileId, null, null, "WHY_HIDDEN"));
    }

    @Transactional
    public CandidateExplanation decisionItem(UUID decisionLogId, UUID candidateProfileId) {
        RankingExplainabilityRepository.RowEvidence row = repository.findDecisionItem(decisionLogId, candidateProfileId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ranking decision item evidence not found"));
        return explain(new RankingExplanationRequest(row.profileId(), candidateProfileId, decisionLogId, row.feedSnapshotId(), "WHY_SHOWN"));
    }

    @Transactional
    public CandidateExplanation explain(RankingExplanationRequest request) {
        validate(request);
        String type = normalizeType(request.explanationType());
        UUID requestId = repository.createRequest(request, type);
        ExplanationBuild build = build(request, type);
        UUID resultId = repository.complete(requestId, request, type, build.evidenceStatus(), build.result());
        return new CandidateExplanation(
            requestId,
            resultId,
            request.profileId(),
            request.candidateProfileId(),
            type,
            build.evidenceStatus(),
            build.evidence(),
            build.reasons(),
            build.result(),
            OffsetDateTime.now()
        );
    }

    private ExplanationBuild build(RankingExplanationRequest request, String type) {
        Optional<RankingExplainabilityRepository.RowEvidence> shown = request.decisionLogId() == null
            ? repository.findLatestShown(request.profileId(), request.candidateProfileId())
            : repository.findDecisionItem(request.decisionLogId(), request.candidateProfileId());
        Optional<RankingExplainabilityRepository.RowEvidence> retrieved = shown.isPresent()
            ? shown
            : repository.findLatestRetrieved(request.profileId(), request.candidateProfileId());
        Optional<String> hardExclusion = hardExclusionService.exclusionReason(request.profileId(), request.candidateProfileId());
        RankingExplainabilityRepository.RowEvidence row = shown.orElseGet(() -> retrieved.orElse(null));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("hardExclusion", hardExclusion.orElse(null));
        evidence.put("shownInFeed", shown.isPresent());
        evidence.put("retrieved", retrieved.isPresent());
        evidence.put("rankedButBelowCutoff", retrieved.isPresent() && shown.isEmpty() && hardExclusion.isEmpty());
        evidence.put("featureValues", row == null ? Map.of() : repository.featureValues(row.featureSnapshotId()));
        evidence.put("sourceTypes", row == null ? List.of() : row.sourceTypes());
        evidence.put("rankingReasons", row == null ? List.of() : row.reasons());
        evidence.put("diversityExplorationAdjustments", row == null ? List.of() : row.diversityAdjustments());
        evidence.put("finalScore", row == null ? null : row.finalScore());
        evidence.put("feedPosition", row == null ? null : row.position());
        evidence.put("rankingVersion", row == null ? null : row.rankingVersion());
        evidence.put("exposureAdjustment", request.candidateProfileId() == null
            ? null
            : exposureRepository.latestAdjustment(request.candidateProfileId(), row == null ? request.decisionLogId() : row.decisionLogId()).orElse(null));
        evidence.put("experimentOrVariantEvidence", "available in ranking_context_json when present on decision log");
        evidence.put("mutation", "NONE");

        List<String> reasons = reasons(type, hardExclusion, shown.isPresent(), retrieved.isPresent(), row);
        String evidenceStatus = row == null && hardExclusion.isEmpty() ? "NOT_AVAILABLE" : (row == null ? "PARTIAL" : "AVAILABLE");
        ExplanationEvidence durable = new ExplanationEvidence(
            row == null ? null : row.retrievalRunId(),
            row == null ? null : row.featureSnapshotRunId(),
            row == null ? null : row.featureSnapshotId(),
            row == null ? request.decisionLogId() : row.decisionLogId(),
            row == null ? request.feedSnapshotId() : row.feedSnapshotId(),
            row == null ? null : row.rankingVersion(),
            evidenceStatus,
            evidence
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("explanationType", type);
        result.put("evidenceStatus", evidenceStatus);
        Map<String, Object> durableIds = new LinkedHashMap<>();
        durableIds.put("retrievalRunId", durable.retrievalRunId());
        durableIds.put("featureSnapshotRunId", durable.featureSnapshotRunId());
        durableIds.put("featureSnapshotId", durable.featureSnapshotId());
        durableIds.put("decisionLogId", durable.decisionLogId());
        durableIds.put("feedSnapshotId", durable.feedSnapshotId());
        durableIds.put("rankingVersion", durable.rankingVersion());
        result.put("durableIds", durableIds);
        result.put("reasons", reasons);
        result.put("evidence", evidence);
        return new ExplanationBuild(durable, evidenceStatus, reasons, result);
    }

    private List<String> reasons(
        String type,
        Optional<String> hardExclusion,
        boolean shown,
        boolean retrieved,
        RankingExplainabilityRepository.RowEvidence row
    ) {
        List<String> reasons = new ArrayList<>();
        if ("WHY_HIDDEN".equals(type)) {
            hardExclusion.ifPresent(reasons::add);
            if (hardExclusion.isEmpty() && !retrieved) {
                reasons.add("NOT_RETRIEVED");
            }
            if (hardExclusion.isEmpty() && retrieved && !shown) {
                reasons.add("RETRIEVED_BUT_NOT_SERVED_OR_BELOW_CUTOFF");
            }
            return reasons.isEmpty() ? List.of("NO_HIDDEN_EVIDENCE_AVAILABLE") : reasons;
        }
        if (hardExclusion.isPresent()) {
            reasons.add("HARD_EXCLUSION_PRESENT");
        }
        if (shown) {
            reasons.add("SERVED_IN_FEED");
        }
        if (row != null && row.sourceTypes() != null && !row.sourceTypes().isEmpty()) {
            reasons.add("SOURCE_ATTRIBUTION_AVAILABLE");
        }
        if (row != null && row.reasons() != null && !row.reasons().isEmpty()) {
            reasons.add("RANKING_REASON_EVIDENCE_AVAILABLE");
        }
        if (row != null && row.diversityAdjustments() != null && !row.diversityAdjustments().isEmpty()) {
            reasons.add("DIVERSITY_OR_EXPLORATION_ADJUSTMENT_EVIDENCE_AVAILABLE");
        }
        if ("WHY_DOWNRANKED".equals(type)) {
            reasons.add("DOWNRANK_EVIDENCE_DERIVED_FROM_RANKING_REASONS_AND_ADJUSTMENTS");
        }
        return reasons.isEmpty() ? List.of("EVIDENCE_NOT_AVAILABLE") : reasons;
    }

    private void validate(RankingExplanationRequest request) {
        if (request == null || request.profileId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileId is required");
        }
        if (request.candidateProfileId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "candidateProfileId is required");
        }
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "GENERIC";
        }
        return switch (type.trim()) {
            case "WHY_SHOWN", "WHY_HIDDEN", "WHY_DOWNRANKED", "GENERIC" -> type.trim();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported explanationType");
        };
    }

    private record ExplanationBuild(
        ExplanationEvidence evidence,
        String evidenceStatus,
        List<String> reasons,
        Map<String, Object> result
    ) {
    }
}
