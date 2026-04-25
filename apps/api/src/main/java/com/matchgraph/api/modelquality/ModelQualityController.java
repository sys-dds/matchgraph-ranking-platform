package com.matchgraph.api.modelquality;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/model-quality")
public class ModelQualityController {

    private final ModelCalibrationService calibrationService;
    private final ModelDriftService driftService;

    public ModelQualityController(ModelCalibrationService calibrationService, ModelDriftService driftService) {
        this.calibrationService = calibrationService;
        this.driftService = driftService;
    }

    @PostMapping("/calibration")
    public CalibrationRun calibrate(@RequestBody CalibrationRequest request) {
        return calibrationService.calibrate(request);
    }

    @GetMapping("/calibration/{runId}")
    public CalibrationRun calibration(@PathVariable UUID runId) {
        return calibrationService.get(runId);
    }

    @PostMapping("/drift")
    public DriftRun drift(@RequestBody DriftRequest request) {
        return driftService.detect(request);
    }

    @GetMapping("/drift/{runId}")
    public DriftRun driftRun(@PathVariable UUID runId) {
        return driftService.get(runId);
    }
}
