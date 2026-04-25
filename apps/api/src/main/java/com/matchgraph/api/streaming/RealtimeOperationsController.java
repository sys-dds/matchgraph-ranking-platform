package com.matchgraph.api.streaming;

import java.util.UUID;

import com.matchgraph.api.streaming.StreamingModels.RealtimeOperationsDemoRun;
import com.matchgraph.api.streaming.StreamingModels.RealtimeRecoveryTrace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RealtimeOperationsController {

    private final RealtimeOperationsDemoService demoService;
    private final RealtimeRecoveryTraceService traceService;

    public RealtimeOperationsController(RealtimeOperationsDemoService demoService, RealtimeRecoveryTraceService traceService) {
        this.demoService = demoService;
        this.traceService = traceService;
    }

    @PostMapping("/demo/realtime-operations/run")
    public RealtimeOperationsDemoRun run() {
        return demoService.run();
    }

    @GetMapping("/demo/realtime-operations/runs/{demoRunId}")
    public RealtimeOperationsDemoRun get(@PathVariable UUID demoRunId) {
        return demoService.get(demoRunId);
    }

    @GetMapping("/realtime/recovery-traces/{traceId}")
    public RealtimeRecoveryTrace trace(@PathVariable UUID traceId) {
        return traceService.get(traceId);
    }
}
