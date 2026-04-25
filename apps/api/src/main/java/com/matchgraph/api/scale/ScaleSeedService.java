package com.matchgraph.api.scale;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ScaleSeedService {

    private final ScaleRepository scaleRepository;

    public ScaleSeedService(ScaleRepository scaleRepository) {
        this.scaleRepository = scaleRepository;
    }

    @Transactional
    public ScaleSeedRun seed(ScaleSeedRequest request) {
        ScaleSeedRequest normalized = request == null ? new ScaleSeedRequest(null, null, null, true, true, null, null, false) : request;
        int profiles = normalized.profileCount() == null ? 25 : normalized.profileCount();
        int edges = normalized.edgeCount() == null ? 50 : normalized.edgeCount();
        int interactions = normalized.interactionCount() == null ? 50 : normalized.interactionCount();
        int clusters = normalized.interestClusterCount() == null ? 5 : normalized.interestClusterCount();
        boolean allowLarge = Boolean.TRUE.equals(normalized.allowLarge());
        if (!allowLarge && (profiles > 500 || edges > 2_000 || interactions > 5_000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "large seed requires allowLarge=true");
        }
        if (profiles > 5_000 || edges > 50_000 || interactions > 100_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seed exceeds local hard cap");
        }
        long seed = normalized.randomSeed() == null ? 42L : normalized.randomSeed();
        ScaleSeedRun run = scaleRepository.createSeedRun(UUID.randomUUID(), normalized, profiles, edges, interactions, clusters, seed, allowLarge);
        Random random = new Random(seed);
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < profiles; index++) {
            UUID profileId = scaleRepository.createProfile("scale-" + seed + "-" + index, "Scale Profile " + index, index % 10 == 0);
            ids.add(profileId);
            scaleRepository.addInterest(profileId, "cluster-" + (index % Math.max(1, clusters)));
            if (Boolean.TRUE.equals(normalized.locationEnabled())) {
                scaleRepository.addLocation(profileId, 51.5 + random.nextDouble(), -0.1 + random.nextDouble());
            }
            if (Boolean.TRUE.equals(normalized.embeddingEnabled())) {
                scaleRepository.addEmbedding(profileId, "scale-v1", vector(random.nextDouble()));
            }
        }
        for (int index = 0; index < edges && ids.size() > 1; index++) {
            scaleRepository.addEdge(ids.get(index % ids.size()), ids.get((index + 1) % ids.size()));
        }
        for (int index = 0; index < interactions && ids.size() > 1; index++) {
            scaleRepository.addInteraction(ids.get(index % ids.size()), ids.get((index + 3) % ids.size()), index);
        }
        scaleRepository.completeSeedRun(run.id(), Map.of("createdProfiles", ids.size(), "deterministic", true));
        return get(run.id());
    }

    public ScaleSeedRun get(UUID seedRunId) {
        return scaleRepository.seedRun(seedRunId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "scale seed run not found"));
    }

    private String vector(double base) {
        return IntStream.range(0, 384)
            .mapToObj(index -> String.valueOf(base + (index / 1000.0d)))
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
