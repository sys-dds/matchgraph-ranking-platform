package com.matchgraph.api.feed;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RankableItemService {

    private static final Set<String> ITEM_TYPES = Set.of("POST", "PROFILE", "PRODUCT", "JOB");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final RankableItemRepository itemRepository;

    public RankableItemService(RankableItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public ItemResponse create(CreateItemRequest request) {
        requireText(request.externalRef(), "externalRef is required");
        requireText(request.title(), "title is required");
        String itemType = requireOneOf(request.itemType(), ITEM_TYPES, "Invalid itemType");
        String status = request.status() == null || request.status().isBlank() ? "ACTIVE" : requireOneOf(request.status(), STATUSES, "Invalid status");
        return itemRepository.create(new CreateItemRequest(request.externalRef(), request.title(), itemType, status));
    }

    public ItemResponse get(UUID id) {
        return itemRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    public List<ItemResponse> find(String itemType, String status, Integer limit) {
        String validType = itemType == null || itemType.isBlank() ? null : requireOneOf(itemType, ITEM_TYPES, "Invalid itemType");
        String validStatus = status == null || status.isBlank() ? null : requireOneOf(status, STATUSES, "Invalid status");
        return itemRepository.find(validType, validStatus, sanitizeLimit(limit, 50));
    }

    public void requireExists(UUID id) {
        if (!itemRepository.exists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found");
        }
    }

    private int sanitizeLimit(Integer limit, int defaultLimit) {
        if (limit == null) {
            return defaultLimit;
        }
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        return limit;
    }

    private String requireOneOf(String value, Set<String> allowed, String message) {
        if (value == null || !allowed.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
