package com.matchgraph.api.synthetic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.matchgraph.api.graph.GraphActionRequest;
import com.matchgraph.api.graph.GraphEdgeService;
import com.matchgraph.api.interaction.InteractionService;
import com.matchgraph.api.interaction.RecordInteractionRequest;
import com.matchgraph.api.profile.CreateProfileRequest;
import com.matchgraph.api.profile.ProfileInterestRequest;
import com.matchgraph.api.profile.ProfileResponse;
import com.matchgraph.api.profile.ProfileService;
import com.matchgraph.api.profile.UpdateProfileInterestsRequest;
import com.matchgraph.api.profile.UpdateProfileLocationRequest;
import com.matchgraph.api.profile.UpsertProfileEmbeddingRequest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyntheticPopulationService {

    private static final int DEFAULT_PROFILE_COUNT = 12;
    private static final int DEFAULT_CLUSTER_COUNT = 3;
    private static final int LOCAL_PROFILE_CAP = 250;
    private static final int EMBEDDING_DIMENSION = 384;

    private final SyntheticPopulationRepository repository;
    private final ProfileService profileService;
    private final GraphEdgeService graphEdgeService;
    private final InteractionService interactionService;

    public SyntheticPopulationService(
        SyntheticPopulationRepository repository,
        ProfileService profileService,
        GraphEdgeService graphEdgeService,
        InteractionService interactionService
    ) {
        this.repository = repository;
        this.profileService = profileService;
        this.graphEdgeService = graphEdgeService;
        this.interactionService = interactionService;
    }

    @Transactional
    public SyntheticPopulationRun create(SyntheticPopulationRequest request) {
        long seed = request == null || request.randomSeed() == null ? 29029L : request.randomSeed();
        int profileCount = request == null || request.profileCount() == null ? DEFAULT_PROFILE_COUNT : request.profileCount();
        int clusterCount = request == null || request.clusterCount() == null ? DEFAULT_CLUSTER_COUNT : request.clusterCount();
        BigDecimal density = request == null || request.compatibilityDensity() == null ? BigDecimal.valueOf(0.35) : request.compatibilityDensity();
        if (profileCount < 2 || profileCount > LOCAL_PROFILE_CAP) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profileCount must be between 2 and " + LOCAL_PROFILE_CAP);
        }
        if (clusterCount < 1 || clusterCount > profileCount) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clusterCount must be between 1 and profileCount");
        }
        UUID runId = repository.createRun(seed, profileCount, clusterCount, density, request == null ? Map.of() : request.config());
        Random random = new Random(seed);
        List<ProfileResponse> profiles = new ArrayList<>();
        for (int i = 0; i < profileCount; i++) {
            int cluster = i % clusterCount;
            ProfileResponse profile = profileService.create(new CreateProfileRequest(
                "synthetic-" + seed + "-" + i,
                "Synthetic " + i,
                "USER",
                "ACTIVE",
                "Synthetic ranking science profile cluster " + cluster,
                "Synthetic City " + cluster,
                "Synthetic Region",
                "Synthetic Country"
            ));
            profileService.updateInterests(profile.id(), new UpdateProfileInterestsRequest(List.of(
                new ProfileInterestRequest("cluster", "cluster-" + cluster, BigDecimal.ONE),
                new ProfileInterestRequest("topic", "topic-" + Math.floorMod(i + cluster, Math.max(1, clusterCount)), BigDecimal.valueOf(0.80))
            )));
            profileService.updateLocation(profile.id(), new UpdateProfileLocationRequest(
                BigDecimal.valueOf(51.0 + cluster * 0.05 + random.nextDouble() * 0.01).setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(-0.1 - cluster * 0.05 - random.nextDouble() * 0.01).setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(10),
                "Synthetic City " + cluster,
                "Synthetic Region",
                "Synthetic Country"
            ));
            profileService.upsertEmbedding(profile.id(), new UpsertProfileEmbeddingRequest(
                "synthetic-v" + seed,
                "deterministic-local-synthetic",
                embedding(seed, i, cluster)
            ));
            repository.insertProfile(
                runId,
                profile.id(),
                "cluster-" + cluster,
                "location-" + cluster,
                Map.of("cluster", cluster, "seed", seed, "preference", preferenceVector(seed, i, cluster))
            );
            profiles.add(profile);
        }
        for (int i = 0; i < profiles.size(); i++) {
            for (int j = 0; j < profiles.size(); j++) {
                if (i == j) {
                    continue;
                }
                int clusterA = i % clusterCount;
                int clusterB = j % clusterCount;
                boolean compatible = clusterA == clusterB || random.nextDouble() < density.doubleValue();
                BigDecimal relevance = compatible
                    ? BigDecimal.valueOf(clusterA == clusterB ? 0.90 : 0.70)
                    : BigDecimal.valueOf(0.10);
                repository.insertLabel(
                    runId,
                    profiles.get(i).id(),
                    profiles.get(j).id(),
                    compatible ? "POSITIVE" : "NEGATIVE",
                    relevance,
                    Map.of("actorCluster", clusterA, "candidateCluster", clusterB, "deterministicSeed", seed)
                );
                if (compatible && j > i && clusterA == clusterB) {
                    graphEdgeService.follow(profiles.get(i).id(), new GraphActionRequest(profiles.get(j).id(), "synthetic community"));
                }
                if (i < Math.min(3, profiles.size()) && j < Math.min(6, profiles.size())) {
                    interactionService.record(profiles.get(i).id(), new RecordInteractionRequest(
                        "synthetic-" + seed + "-" + i + "-" + j,
                        profiles.get(j).id(),
                        compatible ? "LIKE" : "PASS",
                        OffsetDateTime.now(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("syntheticPopulationRunId", runId.toString(), "expectedRelevance", relevance)
                    ));
                }
            }
        }
        repository.completeRun(runId, Map.of(
            "deterministic", true,
            "profilesGenerated", profileCount,
            "interestClustersGenerated", clusterCount,
            "locationClustersGenerated", clusterCount,
            "embeddingsGenerated", profileCount,
            "graphCommunitiesGenerated", clusterCount,
            "groundTruthLabelsGenerated", profileCount * (profileCount - 1),
            "positiveNegativeInteractionLabelsGenerated", true,
            "localSafetyCap", LOCAL_PROFILE_CAP
        ));
        return get(runId);
    }

    public SyntheticPopulationRun get(UUID runId) {
        return repository.findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "synthetic population run not found"));
    }

    private List<Double> embedding(long seed, int index, int cluster) {
        Random random = new Random(seed + index * 31L + cluster * 997L);
        List<Double> values = new ArrayList<>(EMBEDDING_DIMENSION);
        for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
            values.add((cluster + 1) * 0.001 + random.nextDouble() * 0.01);
        }
        return values;
    }

    private List<Double> preferenceVector(long seed, int index, int cluster) {
        Random random = new Random(seed + index * 17L + cluster * 101L);
        return List.of(
            BigDecimal.valueOf(random.nextDouble()).setScale(6, RoundingMode.HALF_UP).doubleValue(),
            BigDecimal.valueOf(cluster).doubleValue(),
            BigDecimal.valueOf(random.nextDouble()).setScale(6, RoundingMode.HALF_UP).doubleValue()
        );
    }
}
