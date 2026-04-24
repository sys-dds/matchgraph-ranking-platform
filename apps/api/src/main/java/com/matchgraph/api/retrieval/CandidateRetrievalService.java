package com.matchgraph.api.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.matchgraph.api.profile.ProfileService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CandidateRetrievalService {

    private static final Map<CandidateSourceType, Integer> DEFAULT_SOURCE_BUDGETS = Map.of(
        CandidateSourceType.RECENTLY_ACTIVE, 20,
        CandidateSourceType.SHARED_INTEREST, 20,
        CandidateSourceType.COLD_START, 10,
        CandidateSourceType.GRAPH_TWO_HOP, 20,
        CandidateSourceType.GRAPH_MUTUALS, 20,
        CandidateSourceType.WEAK_TIE_EXPLORATION, 10,
        CandidateSourceType.VECTOR_SIMILARITY, 20,
        CandidateSourceType.LOCATION_NEARBY, 20
    );

    private final List<CandidateSource> candidateSources;
    private final HardExclusionService hardExclusionService;
    private final RetrievalRepository retrievalRepository;
    private final ProfileService profileService;

    public CandidateRetrievalService(
        List<CandidateSource> candidateSources,
        HardExclusionService hardExclusionService,
        RetrievalRepository retrievalRepository,
        ProfileService profileService
    ) {
        this.candidateSources = candidateSources.stream()
            .sorted(Comparator.comparing(CandidateSource::sourceType))
            .toList();
        this.hardExclusionService = hardExclusionService;
        this.retrievalRepository = retrievalRepository;
        this.profileService = profileService;
    }

    @Transactional
    public CandidateRetrievalRun run(UUID profileId, RunRetrievalRequest request) {
        profileService.requireExists(profileId);
        int limit = sanitizeLimit(request == null ? null : request.limit());
        boolean includeExcluded = request != null && Boolean.TRUE.equals(request.includeExcluded());
        Map<CandidateSourceType, Integer> sourceBudgets = sourceBudgets(request == null ? null : request.perSourceBudgets());
        UUID runId = UUID.randomUUID();
        retrievalRepository.createRun(runId, profileId, limit);

        Map<CandidateSourceType, Integer> sourceCoverage = new EnumMap<>(CandidateSourceType.class);
        List<RetrievedCandidate> rawCandidates = new ArrayList<>();
        for (CandidateSource source : candidateSources) {
            int sourceBudget = sourceBudgets.getOrDefault(source.sourceType(), 0);
            List<RetrievedCandidate> sourcedCandidates = sourceBudget == 0 ? List.of() : source.retrieve(profileId, sourceBudget);
            sourceCoverage.put(source.sourceType(), sourcedCandidates.size());
            rawCandidates.addAll(sourcedCandidates);
        }

        int exclusionCount = 0;
        Map<String, Integer> exclusionCounts = new LinkedHashMap<>();
        for (RetrievedCandidate candidate : rawCandidates) {
            String exclusionReason = hardExclusionService.exclusionReason(profileId, candidate.candidateProfileId()).orElse(null);
            boolean excluded = exclusionReason != null;
            if (excluded) {
                exclusionCount++;
                exclusionCounts.merge(exclusionReason, 1, Integer::sum);
            }
            retrievalRepository.insertItem(
                runId,
                new RetrievedCandidate(
                    candidate.candidateProfileId(),
                    candidate.sourceTypes(),
                    candidate.sourceRank(),
                    excluded,
                    exclusionReason,
                    candidate.sourceScore(),
                    candidate.sourceReason()
                )
            );
        }

        int rawCandidateCount = rawCandidates.size();
        int dedupedCandidateCount = uniqueCandidateCount(rawCandidates);
        List<RetrievedCandidate> finalCandidates = mergeAndLimit(retrievalRepository.mergedCandidates(runId, false), limit);
        Map<String, Object> retrievalQuality = retrievalQuality(
            rawCandidateCount,
            dedupedCandidateCount,
            finalCandidates.size(),
            exclusionCount,
            sourceCoverage,
            sourceBudgets,
            exclusionCounts
        );
        retrievalRepository.completeRun(
            runId,
            rawCandidateCount,
            dedupedCandidateCount,
            finalCandidates.size(),
            exclusionCount,
            exclusionCounts,
            sourceCoverage,
            sourceBudgets,
            retrievalQuality
        );
        return retrievalRepository.findRun(profileId, runId, includeExcluded)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "retrieval run was not persisted"));
    }

    public CandidateRetrievalRun get(UUID profileId, UUID runId) {
        profileService.requireExists(profileId);
        return retrievalRepository.findRun(profileId, runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "retrieval run not found"));
    }

    private List<RetrievedCandidate> mergeAndLimit(List<RetrievedCandidate> candidates, int limit) {
        Map<UUID, RetrievedCandidate> merged = new LinkedHashMap<>();
        for (RetrievedCandidate candidate : candidates) {
            merged.putIfAbsent(candidate.candidateProfileId(), candidate);
        }
        return merged.values().stream()
            .limit(limit)
            .toList();
    }

    private int uniqueCandidateCount(List<RetrievedCandidate> candidates) {
        return (int) candidates.stream()
            .map(RetrievedCandidate::candidateProfileId)
            .distinct()
            .count();
    }

    private Map<CandidateSourceType, Integer> sourceBudgets(Map<CandidateSourceType, Integer> requestedBudgets) {
        Map<CandidateSourceType, Integer> budgets = new EnumMap<>(CandidateSourceType.class);
        DEFAULT_SOURCE_BUDGETS.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> budgets.put(entry.getKey(), entry.getValue()));
        if (requestedBudgets != null) {
            for (Map.Entry<CandidateSourceType, Integer> entry : requestedBudgets.entrySet()) {
                if (entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 100) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "perSourceBudgets must be between 0 and 100");
                }
                budgets.put(entry.getKey(), entry.getValue());
            }
        }
        return budgets;
    }

    private Map<String, Object> retrievalQuality(
        int rawCandidateCount,
        int dedupedCandidateCount,
        int finalCandidateCount,
        int excludedCandidateCount,
        Map<CandidateSourceType, Integer> sourceCoverage,
        Map<CandidateSourceType, Integer> sourceBudgets,
        Map<String, Integer> exclusionCounts
    ) {
        Set<String> emptySourceTypes = sourceCoverage.entrySet().stream()
            .filter(entry -> entry.getValue() == 0)
            .map(entry -> entry.getKey().name())
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> saturatedSourceTypes = sourceCoverage.entrySet().stream()
            .filter(entry -> entry.getValue() >= sourceBudgets.getOrDefault(entry.getKey(), 0) && sourceBudgets.getOrDefault(entry.getKey(), 0) > 0)
            .map(entry -> entry.getKey().name())
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return new LinkedHashMap<>(Map.of(
            "rawCandidateCount", rawCandidateCount,
            "dedupedCandidateCount", dedupedCandidateCount,
            "finalCandidateCount", finalCandidateCount,
            "excludedCandidateCount", excludedCandidateCount,
            "sourceCoverage", sourceCoverage,
            "sourceBudgets", sourceBudgets,
            "exclusionCounts", exclusionCounts,
            "emptySourceTypes", emptySourceTypes,
            "saturatedSourceTypes", saturatedSourceTypes
        ));
    }

    private int sanitizeLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        return limit;
    }
}
