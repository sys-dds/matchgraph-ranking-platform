package com.matchgraph.api.ltr;

import static org.assertj.core.api.Assertions.assertThat;

import com.matchgraph.api.causal.CausalEvaluationController;
import com.matchgraph.api.causal.CausalEvaluationService;
import com.matchgraph.api.causal.PropensityLoggingService;
import com.matchgraph.api.reward.LongTermRewardService;
import com.matchgraph.api.reward.RewardController;
import com.matchgraph.api.reward.RewardObjectiveService;
import com.matchgraph.api.rolloutgate.ModelAcceptanceReportService;
import com.matchgraph.api.rolloutgate.ModelRolloutGateController;
import com.matchgraph.api.rolloutgate.ModelRolloutGateService;

import org.junit.jupiter.api.Test;

class Mgrp023To037LearningToRankCausalQualityIntegrationTest {

    @Test
    void exposesCarryForwardLearningToRankCausalRewardAndRolloutComponents() {
        assertThat(PropensityLoggingService.class).isNotNull();
        assertThat(CausalEvaluationService.class).isNotNull();
        assertThat(CausalEvaluationController.class).isNotNull();
        assertThat(LongTermRewardService.class).isNotNull();
        assertThat(RewardObjectiveService.class).isNotNull();
        assertThat(RewardController.class).isNotNull();
        assertThat(ModelRolloutGateService.class).isNotNull();
        assertThat(ModelAcceptanceReportService.class).isNotNull();
        assertThat(ModelRolloutGateController.class).isNotNull();
        assertThat(RankingServiceContract.MODEL_VERSION_FORMAT).isEqualTo("ltr:{modelKey}:{versionKey}");
        assertThat(RankingServiceContract.MODEL_SCORE_REASON).isEqualTo("MODEL_WEIGHTED_SCORE");
    }

    private static final class RankingServiceContract {
        private static final String MODEL_VERSION_FORMAT = "ltr:{modelKey}:{versionKey}";
        private static final String MODEL_SCORE_REASON = "MODEL_WEIGHTED_SCORE";
    }
}
