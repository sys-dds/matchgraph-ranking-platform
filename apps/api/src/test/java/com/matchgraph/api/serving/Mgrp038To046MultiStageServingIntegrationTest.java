package com.matchgraph.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.CandidateItem;
import com.matchgraph.api.serving.ServingModels.HeavyRankRun;
import com.matchgraph.api.serving.ServingModels.SlateOptimizationRun;

import org.junit.jupiter.api.Test;

class Mgrp038To046MultiStageServingIntegrationTest {

    @Test
    void provesCoreMultiStageDecisionLogicWithoutExternalRuntime() {
        HeavyRankService heavyRankService = new HeavyRankService(new HeavyRankBudgetService());
        HeavyRankRun fallback = heavyRankService.rank(
            "ltr:demo:v1",
            List.of(candidate("a", "GRAPH_MUTUALS", 10), candidate("b", "VECTOR_SIMILARITY", 9)),
            true,
            false
        );
        assertThat(fallback.fallbackUsed()).isTrue();
        assertThat(fallback.fallbackReason()).contains("model unavailable");

        SlateOptimizerService slateOptimizerService = new SlateOptimizerService(new SlateConstraintService());
        SlateOptimizationRun slate = slateOptimizerService.optimize(
            List.of(
                candidate("a", "GRAPH_MUTUALS", 10),
                candidate("b", "GRAPH_MUTUALS", 9),
                candidate("c", "GRAPH_MUTUALS", 8),
                candidate("d", "VECTOR_SIMILARITY", 7)
            ),
            3,
            false
        );
        assertThat(slate.selected()).hasSize(3);
        assertThat(slate.dropped()).anyMatch(item -> "MAX_SAME_SOURCE_TOP_K".equals(item.filteredReason()));
    }

    private CandidateItem candidate(String suffix, String source, int score) {
        return new CandidateItem(UUID.nameUUIDFromBytes(suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8)), source, BigDecimal.valueOf(score), List.of("test"), false, null);
    }
}
