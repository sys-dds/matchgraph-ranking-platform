package com.matchgraph.api.matching;

import java.util.UUID;

import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.ranking.BaselineRankingService;
import com.matchgraph.api.retrieval.CandidateRetrievalService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MatchFeedService {

    private final ProfileService profileService;
    private final CandidateRetrievalService candidateRetrievalService;
    private final BaselineRankingService rankingService;

    public MatchFeedService(ProfileService profileService, CandidateRetrievalService candidateRetrievalService, BaselineRankingService rankingService) {
        this.profileService = profileService;
        this.candidateRetrievalService = candidateRetrievalService;
        this.rankingService = rankingService;
    }

    public RankedFeedResponse feed(UUID profileId, Integer limit) {
        profileService.requireExists(profileId);
        int sanitizedLimit = sanitizeLimit(limit);
        var candidates = candidateRetrievalService.activeCandidates(profileId, sanitizedLimit);
        return new RankedFeedResponse(profileId, sanitizedLimit, rankingService.rank(profileId, candidates, sanitizedLimit));
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
