package com.matchgraph.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.CandidateItem;

import org.junit.jupiter.api.Test;

class SlateOptimizerServiceTest {

    @Test
    void safetyOverrideDropsHardExcludedCandidatesAndReportsPartialSlate() {
        SlateOptimizerService service = new SlateOptimizerService(new SlateConstraintService(), new InMemorySlateOptimizationRepository());
        var result = service.optimize(UUID.randomUUID(), List.of(
            new CandidateItem(UUID.randomUUID(), "GRAPH_MUTUALS", BigDecimal.TEN, List.of(), true, null)
        ), 2, false);
        assertThat(result.selected()).isEmpty();
        assertThat(result.dropped()).hasSize(1);
        assertThat(result.partialResult()).isTrue();
        assertThat(result.warning()).contains("partial slate");
    }

    private static final class InMemorySlateOptimizationRepository extends SlateOptimizationRepository {
        private InMemorySlateOptimizationRepository() {
            super(null, null);
        }

        @Override
        public UUID createRun(UUID requestId, java.util.Map<String, Object> constraints, boolean partial, String warning) {
            return UUID.randomUUID();
        }

        @Override
        public void insertSelected(UUID runId, com.matchgraph.api.serving.ServingModels.ServedItem item, int originalPosition) {
        }

        @Override
        public void insertDropped(UUID runId, CandidateItem item, int originalPosition) {
        }
    }
}
