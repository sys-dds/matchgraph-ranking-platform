package com.matchgraph.api.retrieval;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.profile.ProfileService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CandidateRetrievalService {

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
        this.candidateSources = candidateSources;
        this.hardExclusionService = hardExclusionService;
        this.retrievalRepository = retrievalRepository;
        this.profileService = profileService;
    }

    @Transactional
    public CandidateRetrievalRun run(UUID profileId, RunRetrievalRequest request) {
        profileService.requireExists(profileId);
        int limit = sanitizeLimit(request == null ? null : request.limit());
        UUID runId = UUID.randomUUID();
        retrievalRepository.createRun(runId, profileId, limit);

        Map<CandidateSourceType, Integer> sourceCoverage = new EnumMap<>(CandidateSourceType.class);
        List<RetrievedCandidate> rawCandidates = new ArrayList<>();
        for (CandidateSource source : candidateSources) {
            List<RetrievedCandidate> sourcedCandidates = source.retrieve(profileId, limit);
            sourceCoverage.put(source.sourceType(), sourcedCandidates.size());
            rawCandidates.addAll(sourcedCandidates);
        }

        int exclusionCount = 0;
        for (RetrievedCandidate candidate : rawCandidates) {
            String exclusionReason = hardExclusionService.exclusionReason(profileId, candidate.candidateProfileId()).orElse(null);
            boolean excluded = exclusionReason != null;
            if (excluded) {
                exclusionCount++;
            }
            retrievalRepository.insertItem(
                runId,
                new RetrievedCandidate(
                    candidate.candidateProfileId(),
                    candidate.sourceTypes(),
                    candidate.sourceRank(),
                    excluded,
                    exclusionReason
                )
            );
        }

        List<RetrievedCandidate> finalCandidates = mergeAndLimit(retrievalRepository.mergedCandidates(runId, false), limit);
        retrievalRepository.completeRun(runId, finalCandidates.size(), exclusionCount, sourceCoverage);
        return retrievalRepository.findRun(profileId, runId)
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
