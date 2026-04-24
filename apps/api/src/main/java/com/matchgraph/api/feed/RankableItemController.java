package com.matchgraph.api.feed;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/items")
public class RankableItemController {

    private final RankableItemService itemService;

    public RankableItemController(RankableItemService itemService) {
        this.itemService = itemService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse create(@RequestBody CreateItemRequest request) {
        return itemService.create(request);
    }

    @GetMapping("/{id}")
    public ItemResponse get(@PathVariable UUID id) {
        return itemService.get(id);
    }

    @GetMapping
    public List<ItemResponse> find(
        @RequestParam(required = false) String itemType,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer limit
    ) {
        return itemService.find(itemType, status, limit);
    }
}
