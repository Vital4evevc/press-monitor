package com.ourcrowd.pressmonitor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * A convenience orchestrator used by both the scheduled job and the manual REST trigger:
 * collect and classify, then alert on new coverage. All the processed data — companies,
 * mentions — lives in MySQL, and the frontend's DashboardService reads it live for the
 * dashboard, so there's no separate export step to worry about.
 */
@Service
public class PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private final MonitoringService monitoring;
    private final AlertService alerts;

    public PipelineRunner(MonitoringService monitoring, AlertService alerts) {
        this.monitoring = monitoring;
        this.alerts = alerts;
    }

    // Runs the whole pipeline over all companies and returns the run summary.
    public RunResult runFullPipeline() {
        RunResult result = monitoring.runAll();
        alerts.alertNewMentions(result.newMentions());
        return result;
    }
}
