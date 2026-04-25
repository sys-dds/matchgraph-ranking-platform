package com.matchgraph.api.serving;

import java.util.Map;
import java.util.UUID;

import com.matchgraph.api.serving.ServingModels.ServingTrace;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MultiStageServingTraceService {

    private final MultiStageServingRepository repository;

    public MultiStageServingTraceService(MultiStageServingRepository repository) {
        this.repository = repository;
    }

    public ServingTrace get(UUID traceId) {
        Map<String, Object> trace = repository.trace(traceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "serving trace not found"));
        return new ServingTrace(traceId, trace);
    }
}
