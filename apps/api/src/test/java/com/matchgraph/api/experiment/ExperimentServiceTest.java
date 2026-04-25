package com.matchgraph.api.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.shared.cache.OnlineServingCacheService;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ExperimentServiceTest {

    private final ExperimentRepository repository = mock(ExperimentRepository.class);
    private final ProfileService profileService = mock(ProfileService.class);
    private final OnlineServingCacheService cacheService = mock(OnlineServingCacheService.class);
    private final ExperimentService service = new ExperimentService(repository, profileService, cacheService);

    @Test
    void rejectsInvalidAllocationBoundaries() {
        assertBadRequest(request(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
        assertBadRequest(request(BigDecimal.valueOf(100), BigDecimal.valueOf(101), BigDecimal.ZERO));
        assertBadRequest(request(BigDecimal.valueOf(25), BigDecimal.valueOf(50), BigDecimal.ZERO));
        assertBadRequest(request(BigDecimal.valueOf(100), BigDecimal.TEN, BigDecimal.valueOf(80)));
        assertBadRequest(request(BigDecimal.valueOf(100), BigDecimal.TEN, BigDecimal.valueOf(95)));
    }

    @Test
    void acceptsExactNonHoldoutAllocationBoundaries() {
        RankingExperimentCreateRequest valid = request(BigDecimal.valueOf(100), BigDecimal.TEN, BigDecimal.valueOf(90));
        RankingExperiment created = experiment(valid.trafficPercentage(), valid.holdoutPercentage(), valid.variants().getFirst().allocationPercentage());
        when(repository.create(any(UUID.class), any(RankingExperimentCreateRequest.class))).thenReturn(created);

        assertThat(service.create(valid)).isEqualTo(created);
    }

    @Test
    void persistedAssignmentWinsOverDivergentCache() {
        UUID profileId = UUID.randomUUID();
        RankingExperiment experiment = experiment(BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100));
        RankingExperimentAssignment cached = assignment(experiment, profileId, UUID.randomUUID(), "graph", "v1_graph_affinity", false);
        RankingExperimentAssignment persisted = assignment(experiment, profileId, UUID.randomUUID(), "vector", "v1_vector_affinity", false);
        when(repository.find("mgrp-test")).thenReturn(Optional.of(experiment));
        when(cacheService.assignmentKey(profileId, "mgrp-test")).thenReturn("assignment-key");
        when(cacheService.get(anyString(), eq(RankingExperimentAssignment.class))).thenReturn(Optional.of(cached));
        when(repository.assignment(experiment.id(), profileId)).thenReturn(Optional.of(persisted));

        RankingExperimentAssignment assignment = service.assign(profileId, "mgrp-test");

        assertThat(assignment).isEqualTo(persisted);
        verify(cacheService).putAssignment("assignment-key", persisted);
    }

    private void assertBadRequest(RankingExperimentCreateRequest request) {
        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(ResponseStatusException.class)
            .extracting("statusCode.value")
            .isEqualTo(400);
    }

    private RankingExperimentCreateRequest request(BigDecimal traffic, BigDecimal holdout, BigDecimal allocation) {
        return new RankingExperimentCreateRequest(
            "mgrp-test",
            "MGRP Test",
            "ACTIVE",
            traffic,
            holdout,
            Map.of(),
            List.of(new RankingExperimentVariantRequest("graph", "v1_graph_affinity", allocation, Map.of()))
        );
    }

    private RankingExperiment experiment(BigDecimal traffic, BigDecimal holdout, BigDecimal allocation) {
        UUID experimentId = UUID.randomUUID();
        return new RankingExperiment(
            experimentId,
            "mgrp-test",
            "MGRP Test",
            "ACTIVE",
            traffic,
            holdout,
            Map.of(),
            OffsetDateTime.now(),
            OffsetDateTime.now(),
            List.of(new RankingExperimentVariant(UUID.randomUUID(), experimentId, "graph", "v1_graph_affinity", allocation, Map.of(), OffsetDateTime.now()))
        );
    }

    private RankingExperimentAssignment assignment(
        RankingExperiment experiment,
        UUID profileId,
        UUID assignmentId,
        String variant,
        String rankingVersion,
        boolean holdout
    ) {
        return new RankingExperimentAssignment(
            assignmentId,
            experiment.id(),
            profileId,
            experiment.experimentKey(),
            variant,
            rankingVersion,
            holdout,
            holdout ? "HOLDOUT" : "VARIANT_ALLOCATED",
            "hash",
            OffsetDateTime.now()
        );
    }
}
