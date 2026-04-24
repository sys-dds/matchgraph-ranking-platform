package com.matchgraph.api.graph;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class GraphEdgeService {

    private static final Map<String, String> EDGE_TYPES = Map.of(
        "VIEW", "VIEWED",
        "CLICK", "CLICKED",
        "LIKE", "LIKED",
        "SAVE", "SAVED",
        "DISLIKE", "DISLIKED",
        "HIDE", "HIDDEN"
    );

    private static final Map<String, BigDecimal> STRENGTHS = Map.of(
        "VIEW", BigDecimal.ONE,
        "CLICK", BigDecimal.valueOf(2),
        "LIKE", BigDecimal.valueOf(4),
        "SAVE", BigDecimal.valueOf(5),
        "DISLIKE", BigDecimal.valueOf(3),
        "HIDE", BigDecimal.TEN
    );

    private final GraphEdgeRepository graphEdgeRepository;

    public GraphEdgeService(GraphEdgeRepository graphEdgeRepository) {
        this.graphEdgeRepository = graphEdgeRepository;
    }

    public GraphEdge recordInteractionSignal(UUID profileId, UUID itemId, String interactionType) {
        return graphEdgeRepository.upsert(profileId, itemId, EDGE_TYPES.get(interactionType), STRENGTHS.get(interactionType));
    }
}
