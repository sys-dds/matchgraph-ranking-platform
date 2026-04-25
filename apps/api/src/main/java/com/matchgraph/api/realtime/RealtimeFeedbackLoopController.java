package com.matchgraph.api.realtime;

import java.util.UUID;

import com.matchgraph.api.realtime.RealtimeModels.RealtimeFeedbackDemoRun;
import com.matchgraph.api.realtime.RealtimeModels.RealtimeFeedbackTrace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RealtimeFeedbackLoopController {

    private final RealtimeFeedbackLoopDemoService demoService;
    private final RealtimeFeedbackLoopTraceService traceService;

    public RealtimeFeedbackLoopController(RealtimeFeedbackLoopDemoService demoService, RealtimeFeedbackLoopTraceService traceService) {
        this.demoService = demoService;
        this.traceService = traceService;
    }

    @PostMapping("/demo/realtime-feedback-loop/run")
    public RealtimeFeedbackDemoRun runDemo(@RequestParam UUID profileId, @RequestParam UUID candidateProfileId, @RequestParam(required = false) UUID sessionId, @RequestParam(required = false) UUID feedSnapshotId) {
        return demoService.run(profileId, candidateProfileId, sessionId, feedSnapshotId);
    }

    @GetMapping("/demo/realtime-feedback-loop/runs/{demoRunId}")
    public RealtimeFeedbackDemoRun getDemo(@PathVariable UUID demoRunId) {
        return demoService.get(demoRunId);
    }

    @GetMapping("/realtime/feedback-traces/{traceId}")
    public RealtimeFeedbackTrace trace(@PathVariable UUID traceId) {
        return traceService.get(traceId);
    }
}
