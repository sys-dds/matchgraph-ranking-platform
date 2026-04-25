package com.matchgraph.api.serving;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeavyRankBudgetServiceTest {

    @Test
    void modelUnavailableOrTimeoutRequiresExplicitFallback() {
        HeavyRankBudgetService service = new HeavyRankBudgetService();
        assertThat(service.shouldFallback("ltr:model:v1", true, false)).isTrue();
        assertThat(service.shouldFallback("ltr:model:v1", false, true)).isTrue();
        assertThat(service.shouldFallback("v1_balanced", true, true)).isFalse();
    }
}
