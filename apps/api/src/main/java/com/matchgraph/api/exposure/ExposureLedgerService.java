package com.matchgraph.api.exposure;

import java.util.List;
import java.util.UUID;

import com.matchgraph.api.feed.FeedItem;
import com.matchgraph.api.feed.FeedSnapshot;
import com.matchgraph.api.ranking.RankingDecision;

import org.springframework.stereotype.Service;

@Service
public class ExposureLedgerService {

    private final ExposureRepository repository;

    public ExposureLedgerService(ExposureRepository repository) {
        this.repository = repository;
    }

    public void recordServed(UUID viewerProfileId, FeedSnapshot snapshot, RankingDecision decision, List<FeedItem> items) {
        String experimentKey = stringContext(decision, "experimentKey");
        String variant = stringContext(decision, "assignedVariant");
        for (FeedItem item : items) {
            repository.recordExposure(
                viewerProfileId,
                item.candidateProfileId(),
                snapshot.id(),
                item.id(),
                decision.id(),
                decision.rankingVersion(),
                experimentKey,
                variant,
                "SERVED",
                item.position(),
                "feed:" + snapshot.id() + ":" + item.id()
            );
        }
    }

    public void recomputeWindows(String policyKey) {
        ExposureControlPolicy policy = repository.findPolicy(policyKey).orElse(null);
        if (policy == null) {
            return;
        }
        for (UUID candidateProfileId : repository.exposedCandidateIds()) {
            repository.upsertWindow(policy, candidateProfileId, "daily", 24, policy.dailyCap());
            repository.upsertWindow(policy, candidateProfileId, "rolling_7_days", 24 * 7, policy.rolling7DayCap());
            repository.upsertWindow(policy, candidateProfileId, "policy_window", policy.policyWindowHours(), policy.policyWindowCap());
        }
    }

    private String stringContext(RankingDecision decision, String key) {
        Object value = decision.rankingContext().get(key);
        return value == null ? null : String.valueOf(value);
    }
}
