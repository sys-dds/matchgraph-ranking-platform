package com.matchgraph.api.retrieval;

import java.util.List;
import java.util.UUID;

import com.matchgraph.api.feed.ItemResponse;
import com.matchgraph.api.feed.RankableItemRepository;
import org.springframework.stereotype.Service;

@Service
public class CandidateRetrievalService {

    private final RankableItemRepository itemRepository;

    public CandidateRetrievalService(RankableItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public List<ItemResponse> activeCandidates(UUID profileId, int limit) {
        return itemRepository.findActiveExcludingHidden(profileId, limit);
    }
}
